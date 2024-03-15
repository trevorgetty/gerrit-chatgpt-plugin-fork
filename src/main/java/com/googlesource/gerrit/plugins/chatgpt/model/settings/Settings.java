package com.googlesource.gerrit.plugins.chatgpt.model.settings;

import lombok.Data;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.Set;

@RequiredArgsConstructor
@Data
@Slf4j
public class Settings {
    @NonNull
    private Integer gptAccountId;
    private String gptRequestUserPrompt;
    private Integer commentPropertiesSize;
    @NonNull
    private Integer votingMinScore;
    @NonNull
    private Integer votingMaxScore;

    // Command variables
    private Boolean forcedReview = false;
    private Boolean forcedReviewLastPatchSet = false;
    private Boolean replyFilterEnabled = true;
    private Set<String> directives = new HashSet<>();

}
