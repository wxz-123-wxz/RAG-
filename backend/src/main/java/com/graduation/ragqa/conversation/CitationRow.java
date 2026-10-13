package com.graduation.ragqa.conversation;

public record CitationRow(String id, Integer number, String chunkId, String documentVersionId,
                         String documentTitle, String heading, Integer pageStart,
                         Integer pageEnd, String quote, Double score, Integer chunkNo) {
}
