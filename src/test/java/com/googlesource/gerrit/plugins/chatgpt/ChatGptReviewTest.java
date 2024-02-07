package com.googlesource.gerrit.plugins.chatgpt;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.google.common.net.HttpHeaders;
import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Project;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gerrit.server.data.AccountAttribute;
import com.google.gerrit.server.data.PatchSetAttribute;
import com.google.gerrit.server.events.CommentAddedEvent;
import com.google.gerrit.server.events.PatchSetCreatedEvent;
import com.google.gerrit.server.project.NoSuchProjectException;
import com.google.gson.JsonArray;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.googlesource.gerrit.plugins.chatgpt.client.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.chatgpt.client.gerrit.GerritClient;
import com.googlesource.gerrit.plugins.chatgpt.client.chatgpt.ChatGptClient;
import com.googlesource.gerrit.plugins.chatgpt.client.UriResourceLocator;
import com.googlesource.gerrit.plugins.chatgpt.config.ConfigCreator;
import com.googlesource.gerrit.plugins.chatgpt.config.Configuration;
import com.googlesource.gerrit.plugins.chatgpt.listener.EventListenerHandler;
import com.googlesource.gerrit.plugins.chatgpt.listener.GerritListener;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.entity.ContentType;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static com.google.gerrit.extensions.client.ChangeKind.REWORK;
import static com.googlesource.gerrit.plugins.chatgpt.client.UriResourceLocator.*;
import static java.net.HttpURLConnection.HTTP_OK;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Slf4j
@RunWith(MockitoJUnitRunner.class)
public class ChatGptReviewTest {
    private static final Path basePath = Paths.get("src/test/resources");
    private static final String GERRIT_AUTH_BASE_URL = "http://localhost:9527";
    private static final int GERRIT_GPT_ACCOUNT_ID = 1000000;
    private static final String GERRIT_GPT_USERNAME = "gpt";
    private static final int GERRIT_USER_ACCOUNT_ID = 1000001;
    private static final String GERRIT_USER_ACCOUNT_NAME = "Test";
    private static final String GERRIT_USER_ACCOUNT_EMAIL = "test@example.com";
    private static final String GERRIT_USER_USERNAME = "test";
    private static final String GERRIT_USER_PASSWORD = "test";
    private static final String GERRIT_USER_GROUP = "Test";
    private static final String GPT_TOKEN = "tk-test";
    private static final String GPT_DOMAIN = "http://localhost:9527";
    private static final Project.NameKey PROJECT_NAME = Project.NameKey.parse("myProject");
    private static final Change.Key CHANGE_ID = Change.Key.parse("myChangeId");
    private static final BranchNameKey BRANCH_NAME = BranchNameKey.create(PROJECT_NAME, "myBranchName");
    private static final boolean GPT_STREAM_OUTPUT = true;
    private static final long TEST_TIMESTAMP = 1699270812;
    private static final int VOTING_MIN_SCORE = -1;
    private static final int VOTING_MAX_SCORE = 1;
    private static final String REVIEW_TAG_COMMENTS = "[{\"request\":\"comment 2\",\"id\":0},{\"request\":" +
            "\"message\",\"id\":1,\"filename\":\"test_file.py\",\"lineNumber\":5,\"codeSnippet\":\"TypeClassOrPath\"" +
            "},{\"request\":\"[{\\\"role\\\":\\\"assistant\\\",\\\"content\\\":\\\"message from gpt\\\"},{" +
            "\\\"role\\\":\\\"user\\\",\\\"content\\\":\\\"message 2\\\"}]\",\"id\":2,\"filename\":\"test_file.py\"," +
            "\"lineNumber\":5,\"codeSnippet\":\"TypeClassOrPath\"}]";

    private final Gson gson = new Gson();

    private String expectedResponseStreamed;
    private String expectedSystemPrompt;
    private String reviewUserPrompt;
    private String reviewVoteUserPrompt;
    private String diffContent;
    private String gerritPatchSetReview;

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(9527);

    private PluginConfig globalConfig;
    private PluginConfig projectConfig;

    @Before
    public void before() throws IOException {
        initConfig();
        setupMockRequests();
        initComparisonContent();
    }

    private String getFullChangeId() {
        return new GerritChange(PROJECT_NAME, BRANCH_NAME, CHANGE_ID).getFullChangeId();
    }

    private void initConfig() {
        globalConfig = mock(PluginConfig.class);
        Answer<Object> returnDefaultArgument = invocation -> {
            // Return the second argument (i.e., the Default value) passed to the method
            return invocation.getArgument(1);
        };

        // Mock the Global Config values not provided by Default
        when(globalConfig.getString("gerritAuthBaseUrl")).thenReturn(GERRIT_AUTH_BASE_URL);
        when(globalConfig.getString("gerritUserName")).thenReturn(GERRIT_USER_USERNAME);
        when(globalConfig.getString("gerritPassword")).thenReturn(GERRIT_USER_PASSWORD);
        when(globalConfig.getString("gptToken")).thenReturn(GPT_TOKEN);

        // Mock the Global Config values to the Defaults passed as second arguments of the `get*` methods.
        when(globalConfig.getString(Mockito.anyString(), Mockito.anyString())).thenAnswer(returnDefaultArgument);
        when(globalConfig.getInt(Mockito.anyString(), Mockito.anyInt())).thenAnswer(returnDefaultArgument);
        when(globalConfig.getBoolean(Mockito.anyString(), Mockito.anyBoolean())).thenAnswer(returnDefaultArgument);

        // Mock the Global Config values that differ from the ones provided by Default
        when(globalConfig.getString(Mockito.eq("gptDomain"), Mockito.anyString()))
                .thenReturn(GPT_DOMAIN);
        when(globalConfig.getBoolean(Mockito.eq("gptStreamOutput"), Mockito.anyBoolean()))
                .thenReturn(GPT_STREAM_OUTPUT);
        when(globalConfig.getBoolean(Mockito.eq("gptReviewCommitMessages"), Mockito.anyBoolean()))
                .thenReturn(true);

        projectConfig = mock(PluginConfig.class);

        // Mock the Project Config values
        when(projectConfig.getBoolean(Mockito.eq("isEnabled"), Mockito.anyBoolean())).thenReturn(true);
    }

    private void setupMockRequests() {
        Configuration config = new Configuration(globalConfig, projectConfig);
        String fullChangeId = getFullChangeId();

        // Mock the behavior of the gerritAccountIdUri request
        WireMock.stubFor(WireMock.get(gerritAccountIdUri(GERRIT_GPT_USERNAME))
                .willReturn(WireMock.aResponse()
                        .withStatus(HTTP_OK)
                        .withHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString())
                        .withBody("[{\"_account_id\": " + GERRIT_GPT_ACCOUNT_ID + "}]")));

        // Mock the behavior of the gerritAccountIdUri request
        WireMock.stubFor(WireMock.get(gerritAccountIdUri(GERRIT_USER_USERNAME))
                .willReturn(WireMock.aResponse()
                        .withStatus(HTTP_OK)
                        .withHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString())
                        .withBody("[{\"_account_id\": " + GERRIT_USER_ACCOUNT_ID + "}]")));

        // Mock the behavior of the gerritAccountGroups request
        WireMock.stubFor(WireMock.get(UriResourceLocator.gerritAccountsUri() +
                            gerritGroupPostfixUri(GERRIT_USER_ACCOUNT_ID))
                .willReturn(WireMock.aResponse()
                        .withStatus(HTTP_OK)
                        .withHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString())
                        .withBodyFile("gerritAccountGroups.json")));

        // Mock the behavior of the gerritPatchSetRevisionsUri request
        WireMock.stubFor(WireMock.get(gerritPatchSetRevisionsUri(fullChangeId))
                .willReturn(WireMock.aResponse()
                        .withStatus(HTTP_OK)
                        .withHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString())
                        .withBody("{\"revisions\":{\"aa5be5ebb80846475ec4dfe43e0799eb73c6415a\":{}}}")));

        // Mock the behavior of the gerritGetPatchSetDetailUri request
        WireMock.stubFor(WireMock.get(gerritGetPatchSetDetailUri(fullChangeId))
                .willReturn(WireMock.aResponse()
                        .withStatus(HTTP_OK)
                        .withHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString())
                        .withBodyFile("gerritPatchSetDetail.json")));

        // Mock the behavior of the gerritPatchSetFiles request
        WireMock.stubFor(WireMock.get(gerritPatchSetFilesUri(fullChangeId))
                .willReturn(WireMock.aResponse()
                        .withStatus(HTTP_OK)
                        .withHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString())
                        .withBodyFile("gerritPatchSetFiles.json")));

        // Mock the behavior of the gerritPatchSet diff requests
        WireMock.stubFor(WireMock.get(gerritPatchSetFilesUri(fullChangeId) +
                            gerritDiffPostfixUri("/COMMIT_MSG"))
                .willReturn(WireMock.aResponse()
                        .withStatus(HTTP_OK)
                        .withHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString())
                        .withBodyFile("gerritPatchSetDiffCommitMsg.json")));
        WireMock.stubFor(WireMock.get(gerritPatchSetFilesUri(fullChangeId) +
                            gerritDiffPostfixUri("test_file.py"))
                .willReturn(WireMock.aResponse()
                        .withStatus(HTTP_OK)
                        .withHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString())
                        .withBodyFile("gerritPatchSetDiffTestFile.json")));

        // Mock the behavior of the gerritPatchSet comments request
        WireMock.stubFor(WireMock.get(gerritGetAllPatchSetCommentsUri(fullChangeId))
                .willReturn(WireMock.aResponse()
                        .withStatus(HTTP_OK)
                        .withHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString())
                        .withBodyFile("gerritPatchSetComments.json")));

        // Mock the behavior of the askGpt request
        WireMock.stubFor(WireMock.post(WireMock.urlEqualTo(URI.create(config.getGptDomain()
                        + UriResourceLocator.chatCompletionsUri()).getPath()))
                .willReturn(WireMock.aResponse()
                        .withStatus(HTTP_OK)
                        .withHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString())
                        .withBodyFile("chatGptResponseStreamed.txt")));

        // Mock the behavior of the postReview request
        WireMock.stubFor(WireMock.post(gerritSetReviewUri(fullChangeId))
                .willReturn(WireMock.aResponse()
                        .withStatus(HTTP_OK)));
    }

    private void initComparisonContent() throws IOException {
        diffContent = new String(Files.readAllBytes(basePath.resolve(
                "reducePatchSet/patchSetDiffOutput.json")));
        gerritPatchSetReview = new String(Files.readAllBytes(basePath.resolve(
                "__files/gerritPatchSetReview.json")));
        expectedResponseStreamed = new String(Files.readAllBytes(basePath.resolve(
                "__files/chatGptExpectedResponseStreamed.json")));
        expectedSystemPrompt = Configuration.getDefaultSystemPrompt();
        reviewUserPrompt = String.join("\n", Arrays.asList(
                Configuration.DEFAULT_GPT_REVIEW_USER_PROMPT,
                Configuration.getPatchSetReviewUserPrompt(),
                Configuration.DEFAULT_GPT_COMMIT_MESSAGES_REVIEW_USER_PROMPT,
                diffContent
        ));
        reviewVoteUserPrompt = String.join("\n", Arrays.asList(
                Configuration.DEFAULT_GPT_REVIEW_USER_PROMPT,
                Configuration.getPatchSetReviewUserPrompt(),
                Configuration.DEFAULT_GPT_COMMIT_MESSAGES_REVIEW_USER_PROMPT,
                String.format(Configuration.DEFAULT_GPT_VOTING_REVIEW_USER_PROMPT, VOTING_MIN_SCORE, VOTING_MAX_SCORE),
                diffContent
        ));
    }

    private AccountAttribute createTestAccountAttribute() {
        AccountAttribute accountAttribute = new AccountAttribute();
        accountAttribute.name = GERRIT_USER_ACCOUNT_NAME;
        accountAttribute.username = GERRIT_USER_USERNAME;
        accountAttribute.email = GERRIT_USER_ACCOUNT_EMAIL;
        return accountAttribute;
    }

    private PatchSetAttribute createPatchSetAttribute() {
        PatchSetAttribute patchSetAttribute = new PatchSetAttribute();
        patchSetAttribute.kind = REWORK;
        patchSetAttribute.author = createTestAccountAttribute();
        return patchSetAttribute;
    }

    @Test
    public void patchSetCreatedOrUpdatedStreamed() throws InterruptedException, NoSuchProjectException, ExecutionException {
        Configuration config = new Configuration(globalConfig, projectConfig);
        GerritClient gerritClient = new GerritClient();
        ChatGptClient chatGptClient = new ChatGptClient();
        PatchSetReviewer patchSetReviewer = new PatchSetReviewer(gerritClient, chatGptClient);
        ConfigCreator mockConfigCreator = mock(ConfigCreator.class);
        when(mockConfigCreator.createConfig(ArgumentMatchers.any())).thenReturn(config);

        PatchSetCreatedEvent event = mock(PatchSetCreatedEvent.class);
        when(event.getProjectNameKey()).thenReturn(PROJECT_NAME);
        when(event.getBranchNameKey()).thenReturn(BRANCH_NAME);
        when(event.getChangeKey()).thenReturn(CHANGE_ID);
        when(event.getType()).thenReturn("patchset-created");
        event.patchSet = this::createPatchSetAttribute;
        EventListenerHandler eventListenerHandler = new EventListenerHandler(patchSetReviewer, gerritClient);

        GerritListener gerritListener = new GerritListener(mockConfigCreator, eventListenerHandler);
        gerritListener.onEvent(event);
        CompletableFuture<Void> future = eventListenerHandler.getLatestFuture();
        future.get();

        RequestPatternBuilder requestPatternBuilder = WireMock.postRequestedFor(
                WireMock.urlEqualTo(gerritSetReviewUri(getFullChangeId())));
        List<LoggedRequest> loggedRequests = WireMock.findAll(requestPatternBuilder);
        Assert.assertEquals(1, loggedRequests.size());
        JsonObject gptRequestBody = gson.fromJson(chatGptClient.getRequestBody(), JsonObject.class);
        JsonArray prompts = gptRequestBody.get("messages").getAsJsonArray();
        String systemPrompt = prompts.get(0).getAsJsonObject().get("content").getAsString();
        Assert.assertEquals(expectedSystemPrompt, systemPrompt);
        String userPrompt = prompts.get(1).getAsJsonObject().get("content").getAsString();
        Assert.assertEquals(reviewUserPrompt, userPrompt);
        String requestBody = loggedRequests.get(0).getBodyAsString();
        Assert.assertEquals(expectedResponseStreamed, requestBody);

    }

    @Test
    public void patchSetCreatedOrUpdatedUnstreamed() throws InterruptedException, NoSuchProjectException, ExecutionException {
        when(globalConfig.getBoolean(Mockito.eq("gptStreamOutput"), Mockito.anyBoolean()))
                .thenReturn(false);
        when(globalConfig.getBoolean(Mockito.eq("enabledVoting"), Mockito.anyBoolean()))
                .thenReturn(true);
        Configuration config = new Configuration(globalConfig, projectConfig);
        GerritClient gerritClient = new GerritClient();
        ChatGptClient chatGptClient = new ChatGptClient();
        PatchSetReviewer patchSetReviewer = new PatchSetReviewer(gerritClient, chatGptClient);
        ConfigCreator mockConfigCreator = mock(ConfigCreator.class);
        when(mockConfigCreator.createConfig(ArgumentMatchers.any())).thenReturn(config);
        WireMock.stubFor(WireMock.post(WireMock.urlEqualTo(URI.create(config.getGptDomain()
                        + UriResourceLocator.chatCompletionsUri()).getPath()))
                .willReturn(WireMock.aResponse()
                        .withStatus(HTTP_OK)
                        .withHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString())
                        .withBodyFile("chatGptResponseReview.json")));

        PatchSetCreatedEvent event = mock(PatchSetCreatedEvent.class);
        when(event.getProjectNameKey()).thenReturn(PROJECT_NAME);
        when(event.getBranchNameKey()).thenReturn(BRANCH_NAME);
        when(event.getChangeKey()).thenReturn(CHANGE_ID);
        when(event.getType()).thenReturn("patchset-created");
        event.patchSet = this::createPatchSetAttribute;
        EventListenerHandler eventListenerHandler = new EventListenerHandler(patchSetReviewer, gerritClient);

        GerritListener gerritListener = new GerritListener(mockConfigCreator, eventListenerHandler);
        gerritListener.onEvent(event);
        CompletableFuture<Void> future = eventListenerHandler.getLatestFuture();
        future.get();

        RequestPatternBuilder requestPatternBuilder = WireMock.postRequestedFor(
                WireMock.urlEqualTo(gerritSetReviewUri(getFullChangeId())));
        List<LoggedRequest> loggedRequests = WireMock.findAll(requestPatternBuilder);
        Assert.assertEquals(1, loggedRequests.size());
        JsonObject gptRequestBody = gson.fromJson(chatGptClient.getRequestBody(), JsonObject.class);
        JsonArray prompts = gptRequestBody.get("messages").getAsJsonArray();
        String userPrompt = prompts.get(1).getAsJsonObject().get("content").getAsString();
        Assert.assertEquals(reviewVoteUserPrompt, userPrompt);
        String requestBody = loggedRequests.get(0).getBodyAsString();
        Assert.assertEquals(gerritPatchSetReview, requestBody);

    }

    @Test
    public void patchSetDisableUserGroup() throws NoSuchProjectException {
        when(globalConfig.getString(Mockito.eq("disabledGroups"), Mockito.anyString()))
                .thenReturn(GERRIT_USER_GROUP);
        Configuration config = new Configuration(globalConfig, projectConfig);
        GerritClient gerritClient = new GerritClient();
        ChatGptClient chatGptClient = new ChatGptClient();
        PatchSetReviewer patchSetReviewer = new PatchSetReviewer(gerritClient, chatGptClient);
        ConfigCreator mockConfigCreator = mock(ConfigCreator.class);
        when(mockConfigCreator.createConfig(ArgumentMatchers.any())).thenReturn(config);

        PatchSetCreatedEvent event = mock(PatchSetCreatedEvent.class);
        when(event.getProjectNameKey()).thenReturn(PROJECT_NAME);
        when(event.getBranchNameKey()).thenReturn(BRANCH_NAME);
        when(event.getChangeKey()).thenReturn(CHANGE_ID);
        when(event.getType()).thenReturn("patchset-created");
        event.patchSet = this::createPatchSetAttribute;
        EventListenerHandler eventListenerHandler = new EventListenerHandler(patchSetReviewer, gerritClient);

        GerritListener gerritListener = new GerritListener(mockConfigCreator, eventListenerHandler);
        gerritListener.onEvent(event);
        CompletableFuture<Void> future = eventListenerHandler.getLatestFuture();
        Assert.assertThrows(NullPointerException.class, () -> future.get());

    }

    @Test
    public void gptMentionedInComment() throws InterruptedException, NoSuchProjectException, ExecutionException {
        Configuration config = new Configuration(globalConfig, projectConfig);
        GerritClient gerritClient = new GerritClient();
        ChatGptClient chatGptClient = new ChatGptClient();
        PatchSetReviewer patchSetReviewer = new PatchSetReviewer(gerritClient, chatGptClient);
        ConfigCreator mockConfigCreator = mock(ConfigCreator.class);
        when(config.getGerritUserName()).thenReturn(GERRIT_GPT_USERNAME);
        when(mockConfigCreator.createConfig(ArgumentMatchers.any())).thenReturn(config);
        WireMock.stubFor(WireMock.post(WireMock.urlEqualTo(URI.create(config.getGptDomain()
                        + UriResourceLocator.chatCompletionsUri()).getPath()))
                .willReturn(WireMock.aResponse()
                        .withStatus(HTTP_OK)
                        .withHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString())
                        .withBodyFile("chatGptResponseComments.json")));

        CommentAddedEvent event = mock(CommentAddedEvent.class);
        when(event.getProjectNameKey()).thenReturn(PROJECT_NAME);
        when(event.getBranchNameKey()).thenReturn(BRANCH_NAME);
        when(event.getChangeKey()).thenReturn(CHANGE_ID);
        when(event.getType()).thenReturn("comment-added");
        event.author = this::createTestAccountAttribute;
        event.patchSet = this::createPatchSetAttribute;
        event.eventCreatedOn = TEST_TIMESTAMP;
        EventListenerHandler eventListenerHandler = new EventListenerHandler(patchSetReviewer, gerritClient);

        GerritListener gerritListener = new GerritListener(mockConfigCreator, eventListenerHandler);
        gerritListener.onEvent(event);
        CompletableFuture<Void> future = eventListenerHandler.getLatestFuture();
        future.get();

        String commentUserPrompt = String.join("\n", Arrays.asList(
                Configuration.DEFAULT_GPT_REQUEST_USER_PROMPT_1,
                diffContent,
                Configuration.DEFAULT_GPT_REQUEST_USER_PROMPT_2,
                REVIEW_TAG_COMMENTS,
                config.getCommentRequestUserPrompt()
        ));
        RequestPatternBuilder requestPatternBuilder = WireMock.postRequestedFor(
                WireMock.urlEqualTo(gerritSetReviewUri(getFullChangeId())));
        List<LoggedRequest> loggedRequests = WireMock.findAll(requestPatternBuilder);
        Assert.assertEquals(1, loggedRequests.size());
        JsonObject gptRequestBody = gson.fromJson(chatGptClient.getRequestBody(), JsonObject.class);
        JsonArray prompts = gptRequestBody.get("messages").getAsJsonArray();
        String userPrompt = prompts.get(1).getAsJsonObject().get("content").getAsString();
        Assert.assertEquals(commentUserPrompt, userPrompt);

    }

}
