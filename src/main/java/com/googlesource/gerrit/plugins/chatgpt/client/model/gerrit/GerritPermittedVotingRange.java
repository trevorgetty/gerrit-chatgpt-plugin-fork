package com.googlesource.gerrit.plugins.chatgpt.client.model.gerrit;

import lombok.Data;

@Data
public class GerritPermittedVotingRange {
    private int min;
    private int max;
}
