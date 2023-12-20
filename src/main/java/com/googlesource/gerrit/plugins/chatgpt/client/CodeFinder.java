package com.googlesource.gerrit.plugins.chatgpt.client;

import com.googlesource.gerrit.plugins.chatgpt.client.model.ChatGptSuggestionPoint;
import com.googlesource.gerrit.plugins.chatgpt.client.model.CodeFinderDiff;
import com.googlesource.gerrit.plugins.chatgpt.client.model.DiffContent;
import com.googlesource.gerrit.plugins.chatgpt.client.model.GerritCodeRange;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.util.List;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class CodeFinder {
    private final List<CodeFinderDiff> codeFinderDiffs;
    private int commentedLine;
    private Pattern commentedCodePattern;
    private GerritCodeRange currentCodeRange;
    private GerritCodeRange closestCodeRange;

    public CodeFinder(List<CodeFinderDiff> codeFinderDiffs) {
        this.codeFinderDiffs = codeFinderDiffs;
    }

    private void updateCodePattern(ChatGptSuggestionPoint suggestion) {
        String commentedCode = suggestion.getCodeSnippet().trim();
        String commentedCodeRegex = Pattern.quote(commentedCode);
        commentedCodePattern = Pattern.compile(commentedCodeRegex);
    }

    private double calcCodeDistance(GerritCodeRange range, int fromLine) {
        return Math.abs((range.end_line - range.start_line) / 2 - fromLine);
    }

    private String getDiffItem(Field diffField, DiffContent diffItem) {
        try {
            return (String) diffField.get(diffItem);
        }
        catch (IllegalAccessException e) {
            log.error("Error while processing file difference (diff type: {})", diffField.getName(), e);
            return null;
        }
    }

    private int getLineNumber(TreeMap<Integer, Integer> charToLineMapItem, int position) {
        Integer floorPosition = charToLineMapItem.floorKey(position);
        if (floorPosition == null) {
            throw new IllegalArgumentException("Position: " + position);
        }
        return charToLineMapItem.get(floorPosition);
    }

    private int getLineCharacter(String diffCode, int position) {
        // Return the offset relative to the nearest preceding newline character if found, `position` otherwise
        return position - diffCode.substring(0, position).lastIndexOf("\n") -1;
    }

    private void findCodeLines(String diffCode, TreeMap<Integer, Integer> charToLineMapItem)
            throws IllegalArgumentException {
        Matcher codeMatcher = commentedCodePattern.matcher(diffCode);
        while (codeMatcher.find()) {
            int startPosition = codeMatcher.start();
            int endPosition = codeMatcher.end();
            currentCodeRange = GerritCodeRange.builder()
                    .start_line(getLineNumber(charToLineMapItem, startPosition))
                    .end_line(getLineNumber(charToLineMapItem, endPosition))
                    .start_character(getLineCharacter(diffCode, startPosition))
                    .end_character(getLineCharacter(diffCode, endPosition))
                    .build();
            // If multiple commented code portions are found and currentCommentRange is closer to the line
            // number suggested by ChatGPT than closestCommentRange, it becomes the new closestCommentRange
            if (closestCodeRange == null || calcCodeDistance(currentCodeRange, commentedLine) <
                    calcCodeDistance(closestCodeRange, commentedLine)) {
                closestCodeRange = currentCodeRange.toBuilder().build();
            }
        }
    }

    public GerritCodeRange findCommentedCode(ChatGptSuggestionPoint suggestion, int commentedLine) {
        this.commentedLine = commentedLine;
        updateCodePattern(suggestion);
        currentCodeRange = null;
        closestCodeRange = null;
        for (CodeFinderDiff codeFinderDiff : codeFinderDiffs) {
            for (Field diffField : DiffContent.class.getDeclaredFields()) {
                String diffCode = getDiffItem(diffField, codeFinderDiff.getContent());
                if (diffCode != null) {
                    TreeMap<Integer, Integer> charToLineMapItem = codeFinderDiff.getCharToLineMap();
                    try {
                        findCodeLines(diffCode, charToLineMapItem);
                    }
                    catch (IllegalArgumentException e) {
                        log.warn("Could not retrieve line number from charToLineMap {}", charToLineMapItem, e);
                    }
                }
            }
        }

        return closestCodeRange;
    }

}
