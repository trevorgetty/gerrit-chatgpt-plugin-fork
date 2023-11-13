package com.googlesource.gerrit.plugins.chatgpt.listener;

import com.google.common.base.Splitter;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Project;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.ChangeEvent;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.chatgpt.PatchSetReviewer;
import com.googlesource.gerrit.plugins.chatgpt.client.GerritClient;
import com.googlesource.gerrit.plugins.chatgpt.config.Configuration;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.*;

@Slf4j
public class EventListenerHandler {

    private final PatchSetReviewer reviewer;
    private final BlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(100);
    private final RejectedExecutionHandler handler = new ThreadPoolExecutor.CallerRunsPolicy();
    private final ThreadFactory threadFactory = new ThreadFactoryBuilder()
            .setNameFormat("EventListenerHandler-%d")
            .build();
    private final ExecutorService executorService = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, queue, threadFactory, handler);
    private final GerritClient gerritClient;
    private CompletableFuture<Void> latestFuture;

    @Inject
    public EventListenerHandler(PatchSetReviewer reviewer, GerritClient gerritClient) {
        this.reviewer = reviewer;
        this.gerritClient = gerritClient;

        addShutdownHoot();
    }

    public static String buildFullChangeId(Project.NameKey projectName, BranchNameKey branchName, Change.Key changeKey) {
        return String.join("~", projectName.get(), branchName.shortName(), changeKey.get());
    }

    private void addShutdownHoot() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException ex) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }));
    }

    public void handleEvent(Configuration config, Event event) {
        ChangeEvent changeEvent = (ChangeEvent) event;
        String eventType = event.getType();
        log.info("Event type {}", eventType);
        Project.NameKey projectNameKey = changeEvent.getProjectNameKey();
        BranchNameKey branchNameKey = changeEvent.getBranchNameKey();
        Change.Key changeKey = changeEvent.getChangeKey();

        String fullChangeId = buildFullChangeId(projectNameKey, branchNameKey, changeKey);

        gerritClient.initialize(config);

        List<String> enabledProjects = Splitter.on(",").omitEmptyStrings()
                .splitToList(config.getEnabledProjects());
        if (!config.isGlobalEnable() &&
                !enabledProjects.contains(projectNameKey.get()) &&
                !config.isProjectEnable()) {
            log.info("The project {} is not enabled for review", projectNameKey);
            return;
        }

        if ("comment-added".equals(eventType)) {
            if (!gerritClient.retrieveLastComments(event, fullChangeId)) {
                return;
            }
        }

        // Execute the potentially time-consuming operation asynchronously
        latestFuture = CompletableFuture.runAsync(() -> {
            try {
                log.info("Processing change: {}", fullChangeId);
                reviewer.review(config, fullChangeId);
                log.info("Finished processing change: {}", fullChangeId);
            } catch (Exception e) {
                log.error("Error while processing change: {}", fullChangeId, e);
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        }, executorService);
    }

    public CompletableFuture<Void> getLatestFuture() {
        return latestFuture;
    }

}
