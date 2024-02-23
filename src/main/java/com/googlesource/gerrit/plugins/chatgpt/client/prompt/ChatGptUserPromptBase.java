package com.googlesource.gerrit.plugins.chatgpt.client.prompt;

import com.googlesource.gerrit.plugins.chatgpt.client.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.chatgpt.client.patch.code.InlineCode;
import com.googlesource.gerrit.plugins.chatgpt.client.patch.diff.FileDiffProcessed;
import com.googlesource.gerrit.plugins.chatgpt.config.Configuration;
import com.googlesource.gerrit.plugins.chatgpt.model.chatgpt.ChatGptMessageItem;
import com.googlesource.gerrit.plugins.chatgpt.model.common.CommentData;
import com.googlesource.gerrit.plugins.chatgpt.model.gerrit.GerritComment;
import com.googlesource.gerrit.plugins.chatgpt.model.common.GerritClientData;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

@Slf4j
public abstract class ChatGptUserPromptBase {
    protected final GerritClientData gerritClientData;
    protected final HashMap<String, FileDiffProcessed> fileDiffsProcessed;
    protected final CommentData commentData;
    @Getter
    protected final List<ChatGptMessageItem> messageItems;

    protected ChatGptHistory gptMessageHistory;
    @Getter
    protected List<GerritComment> commentProperties;

    public ChatGptUserPromptBase(Configuration config, GerritChange change, GerritClientData gerritClientData) {
        this.gerritClientData = gerritClientData;
        fileDiffsProcessed = gerritClientData.getFileDiffsProcessed();
        commentData = gerritClientData.getCommentData();
        gptMessageHistory = new ChatGptHistory(config, change, gerritClientData);
        messageItems = new ArrayList<>();
    }

    abstract void addMessageItem(int i);

    protected ChatGptMessageItem getMessageItem(int i) {
        ChatGptMessageItem messageItem = new ChatGptMessageItem();
        GerritComment commentProperty = commentProperties.get(i);
        if (commentProperty.getLine() != null || commentProperty.getRange() != null) {
            String filename = commentProperty.getFilename();
            InlineCode inlineCode = new InlineCode(fileDiffsProcessed.get(filename));
            messageItem.setFilename(filename);
            messageItem.setLineNumber(commentProperty.getLine());
            messageItem.setCodeSnippet(inlineCode.getInlineCode(commentProperty));
        }

        return messageItem;
    }

}
