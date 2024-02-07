package com.googlesource.gerrit.plugins.chatgpt.client.gerrit;

import com.googlesource.gerrit.plugins.chatgpt.client.FileDiffProcessed;
import com.googlesource.gerrit.plugins.chatgpt.client.model.ReviewBatch;
import com.googlesource.gerrit.plugins.chatgpt.client.model.gerrit.GerritComment;
import com.googlesource.gerrit.plugins.chatgpt.config.Configuration;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;

@Slf4j
public class GerritClientFacade {
    private final GerritClientPatchSet gerritClientPatchSet;
    private final GerritClientComments gerritClientComments;
    private final GerritClientReview gerritClientReview;

    public GerritClientFacade(Configuration config) {
        config.resetDynamicConfiguration();
        gerritClientPatchSet = new GerritClientPatchSet(config);
        gerritClientComments = new GerritClientComments(config);
        gerritClientReview = new GerritClientReview(config);
    }

    public String getPatchSet(GerritChange change) throws Exception {
        return gerritClientPatchSet.getPatchSet(change);
    }

    public boolean getForcedReview() {
        return gerritClientComments.getForcedReview();
    }

    public boolean isDisabledUser(String authorUsername) {
        return gerritClientPatchSet.isDisabledUser(authorUsername);
    }

    public boolean isDisabledTopic(String topic) {
        return gerritClientPatchSet.isDisabledTopic(topic);
    }

    public HashMap<String, FileDiffProcessed> getFileDiffsProcessed() {
        return gerritClientPatchSet.getFileDiffsProcessed();
    }

    public Integer getGptAccountId() {
        return gerritClientComments.getGptAccountId();
    }

    public List<GerritComment> getCommentProperties() {
        return gerritClientComments.getCommentProperties();
    }

    public void setReview(String fullChangeId, List<ReviewBatch> reviewBatches, Integer reviewScore) throws Exception {
        gerritClientReview.setReview(fullChangeId, reviewBatches, reviewScore);
    }

    public boolean retrieveLastComments(GerritChange change) {
        return gerritClientComments.retrieveLastComments(change);
    }

    public String getUserRequests() {
        HashMap<String, FileDiffProcessed> fileDiffsProcessed = gerritClientPatchSet.getFileDiffsProcessed();
        return gerritClientComments.getUserRequests(fileDiffsProcessed);
    }

}
