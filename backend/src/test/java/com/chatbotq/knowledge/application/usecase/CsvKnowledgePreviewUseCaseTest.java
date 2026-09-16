package com.chatbotq.knowledge.application.usecase;

import com.chatbotq.knowledge.application.model.KnowledgeCsvImportStrategy;
import com.chatbotq.knowledge.application.model.KnowledgeCsvPreview;
import com.chatbotq.knowledge.application.model.KnowledgeCsvPreviewRow;
import com.chatbotq.knowledge.application.port.KnowledgeCsvPreviewPort;
import com.chatbotq.knowledge.infrastructure.csv.ApacheCommonsCsvKnowledgeParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CsvKnowledgePreviewUseCaseTest {
    @Test
    void providesTheExactUtf8Template() {
        KnowledgeCsvPreviewPort preview = preview(4000);

        assertArrayEquals("question,answer,external_id,active\r\n".getBytes(StandardCharsets.UTF_8), preview.template());
        assertEquals(0, preview.preview("question,answer\r\nHours?,9 to 5\r\n".getBytes(StandardCharsets.UTF_8),
            KnowledgeCsvImportStrategy.CREATE_ONLY).getInvalidRowCount());
    }

    @Test
    void previewsBomQuotedValuesAndEveryAllowedHeaderVariant() {
        KnowledgeCsvPreviewPort preview = preview(4000);
        List<String> files = Arrays.asList(
            "\uFEFFquestion,answer\r\n\" What is it? \",\" A, answer \"\"quoted\"\" \"\r\n",
            "question,answer,external_id\r\nQ,A, id-1 \r\n",
            "question,answer,active\r\nQ,A,false\r\n",
            "question,answer,external_id,active\r\nQ,A,id-1,true\r\n");

        for (String file : files) {
            KnowledgeCsvPreview result = preview.preview(file.getBytes(StandardCharsets.UTF_8),
                KnowledgeCsvImportStrategy.CREATE_ONLY);
            assertEquals(1, result.getValidRowCount());
            assertEquals(0, result.getInvalidRowCount());
        }
    }

    @Test
    void reportsSafeFileErrorsForMalformedUtf8SyntaxAndHeaders() {
        KnowledgeCsvPreviewPort preview = preview(4000);

        assertFileError(preview.preview(new byte[] {(byte) 0xc3, (byte) 0x28}, KnowledgeCsvImportStrategy.CREATE_ONLY),
            "invalid_utf8");
        assertFileError(preview.preview("question,answer\r\n\"unterminated".getBytes(StandardCharsets.UTF_8),
            KnowledgeCsvImportStrategy.CREATE_ONLY), "csv_syntax");
        assertFileError(preview.preview("answer,question\r\nA,Q\r\n".getBytes(StandardCharsets.UTF_8),
            KnowledgeCsvImportStrategy.CREATE_ONLY), "invalid_headers");
    }

    @Test
    void reportsFileTooLargeOnlyAboveTheExactRawByteBoundaryBeforeParsing() {
        String accepted = "question,answer\r\nQ,A\r\n";
        KnowledgeCsvPreviewPort preview = new CsvKnowledgePreviewUseCase(
            new ApacheCommonsCsvKnowledgeParser(accepted.getBytes(StandardCharsets.UTF_8).length, 10), 4000);

        assertEquals(1, preview.preview(accepted.getBytes(StandardCharsets.UTF_8),
            KnowledgeCsvImportStrategy.CREATE_ONLY).getValidRowCount());
        assertFileError(preview.preview((accepted + " ").getBytes(StandardCharsets.UTF_8),
            KnowledgeCsvImportStrategy.CREATE_ONLY), "file_too_large");
    }

    @Test
    void reportsTooManyRowsAtTheConfiguredDataRowBoundaryWithoutReturningRows() {
        KnowledgeCsvPreviewPort preview = new CsvKnowledgePreviewUseCase(new ApacheCommonsCsvKnowledgeParser(1000, 2), 4000);
        String accepted = "question,answer\r\nQ1,A1\r\nQ2,A2\r\n";
        String rejected = accepted + "Q3,A3\r\n";

        assertEquals(2, preview.preview(accepted.getBytes(StandardCharsets.UTF_8),
            KnowledgeCsvImportStrategy.CREATE_ONLY).getRows().size());
        assertFileError(preview.preview(rejected.getBytes(StandardCharsets.UTF_8),
            KnowledgeCsvImportStrategy.CREATE_ONLY), "csv_limits_exceeded");
    }

    @Test
    void rejectsColumnHeavyHeadersBeforeMaterializingAnyPreviewData() {
        KnowledgeCsvPreviewPort preview = previewWithLexicalLimits(2, 4, 64);
        String marker = "HEADER_SECRET_SHOULD_NOT_LEAK";

        KnowledgeCsvPreview result = preview.preview(("question,answer,external_id,active," + marker + "\nQ,A,id,true,x\n")
            .getBytes(StandardCharsets.UTF_8), KnowledgeCsvImportStrategy.CREATE_ONLY);

        assertLexicalLimitError(result, marker);
    }

    @Test
    void rejectsColumnHeavyRowsBeforeMaterializingAnyPreviewData() {
        KnowledgeCsvPreviewPort preview = previewWithLexicalLimits(2, 4, 64);
        String marker = "ROW_SECRET_SHOULD_NOT_LEAK";

        KnowledgeCsvPreview result = preview.preview(("question,answer\nQ,A," + marker + ",extra,overflow\n")
            .getBytes(StandardCharsets.UTF_8), KnowledgeCsvImportStrategy.CREATE_ONLY);

        assertLexicalLimitError(result, marker);
    }

    @Test
    void rejectsOversizedCellsBeforeMaterializingAnyPreviewData() {
        KnowledgeCsvPreviewPort preview = previewWithLexicalLimits(2, 4, 8);
        String marker = "CELL_SECRET_SHOULD_NOT_LEAK";

        KnowledgeCsvPreview result = preview.preview(("question,answer\nQ," + marker + "\n")
            .getBytes(StandardCharsets.UTF_8), KnowledgeCsvImportStrategy.CREATE_ONLY);

        assertLexicalLimitError(result, marker);
    }

    @Test
    void acceptsExactLexicalColumnCellAndRowBoundariesIncludingLf() {
        String answer = repeat("x", 32);
        String file = "question,answer,external_id,active\nQ," + answer + ",id,true\nQ2,A2,id2,false\n";
        KnowledgeCsvPreviewPort preview = previewWithLexicalLimits(2, 4, 32);

        KnowledgeCsvPreview result = preview.preview(file.getBytes(StandardCharsets.UTF_8), KnowledgeCsvImportStrategy.CREATE_ONLY);

        assertEquals(2, result.getValidRowCount());
        assertEquals(0, result.getInvalidRowCount());
    }

    @Test
    void acceptsDefaultCellBudgetForEightThousandFourByteUnicodeCodePoints() {
        String answer = repeat("🙂", 8000);
        ApacheCommonsCsvKnowledgeParser parser = new ApacheCommonsCsvKnowledgeParser(100_000, 1);

        assertTrue(parser.parse(("question,answer\nQ," + answer + "\n").getBytes(StandardCharsets.UTF_8))
            .getFileErrors().isEmpty());
    }

    @Test
    void rejectsOverRowLimitBeforeCommonsCsvCanParseAnotherRecord() {
        KnowledgeCsvPreviewPort preview = previewWithLexicalLimits(2, 4, 64);
        String marker = "ROW_LIMIT_SECRET_SHOULD_NOT_LEAK";

        KnowledgeCsvPreview result = preview.preview(("question,answer\nQ1,A1\nQ2,A2\n" + marker + ",A3\n")
            .getBytes(StandardCharsets.UTF_8), KnowledgeCsvImportStrategy.CREATE_ONLY);

        assertLexicalLimitError(result, marker);
    }

    @Test
    void rejectsNonPositiveConfiguredLimitsFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> new ApacheCommonsCsvKnowledgeParser(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new ApacheCommonsCsvKnowledgeParser(1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ApacheCommonsCsvKnowledgeParser(1, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new ApacheCommonsCsvKnowledgeParser(1, 1, 1, 0));
    }

    @Test
    void reportsColumnAndFieldErrorsWithoutExposingCellValues() {
        KnowledgeCsvPreviewPort preview = preview(4);
        String tooLongQuestion = repeat("x", 2001);
        String file = "question,answer,external_id,active\r\n"
            + "Q\r\n"
            + ",A,id,true\r\n"
            + "Q,,id,true\r\n"
            + "Q,A,id,TRUE\r\n"
            + "Q\u0001,A,id,true\r\n"
            + tooLongQuestion + ",A,id,true\r\n"
            + "🙂🙂,A,id,true\r\n";

        KnowledgeCsvPreview result = preview.preview(file.getBytes(StandardCharsets.UTF_8), KnowledgeCsvImportStrategy.CREATE_ONLY);

        assertEquals(0, result.getValidRowCount());
        assertEquals(7, result.getInvalidRowCount());
        assertRowError(result, 2, "invalid_column_count");
        assertRowError(result, 3, "required");
        assertRowError(result, 4, "required");
        assertRowError(result, 5, "invalid_active");
        assertRowError(result, 6, "control_character");
        assertRowError(result, 7, "length");
        assertRowError(result, 8, "embedding_token_limit");
        assertFalse(result.getRows().get(4).getErrors().get(0).getMessage().contains("TRUE"));
    }

    @Test
    void treatsQuotedMultilineCellsAsOneLogicalRowButRejectsTheirControlCharacter() {
        KnowledgeCsvPreviewPort preview = preview(4000);

        KnowledgeCsvPreview result = preview.preview("question,answer\r\n\"first\nsecond\",A\r\nQ,A\r\n".getBytes(StandardCharsets.UTF_8),
            KnowledgeCsvImportStrategy.CREATE_ONLY);

        assertEquals(2, result.getRows().size());
        assertEquals(2, result.getRows().get(0).getRowNumber());
        assertRowError(result, 2, "control_character");
        assertEquals(3, result.getRows().get(1).getRowNumber());
        assertTrue(result.getRows().get(1).isValid());
    }

    @Test
    void acceptsLfRecordsAsWellAsCrLfRecords() {
        KnowledgeCsvPreviewPort preview = preview(4000);

        assertEquals(1, preview.preview("question,answer\nQ,A\n".getBytes(StandardCharsets.UTF_8),
            KnowledgeCsvImportStrategy.CREATE_ONLY).getValidRowCount());
    }

    @Test
    void requiresExternalIdsForUpsertAndInvalidatesEveryNormalizedDuplicate() {
        KnowledgeCsvPreviewPort preview = preview(4000);
        KnowledgeCsvPreview upsert = preview.preview(("question,answer,external_id\r\n"
            + "Q,A,  duplicate  \r\nQ2,A2,duplicate\r\nQ3,A3,\r\n").getBytes(StandardCharsets.UTF_8),
            KnowledgeCsvImportStrategy.UPSERT);

        assertEquals(0, upsert.getValidRowCount());
        assertEquals(3, upsert.getInvalidRowCount());
        assertRowError(upsert, 2, "duplicate_external_id");
        assertRowError(upsert, 3, "duplicate_external_id");
        assertRowError(upsert, 4, "external_id_required_for_upsert");
    }

    @Test
    void returnsImmutableSafeInMemoryRowsAndDefaultsBlankActiveToTrue() {
        KnowledgeCsvPreviewPort preview = preview(4000);
        KnowledgeCsvPreview result = preview.preview("question,answer,active\r\n Q , A ,\r\n".getBytes(StandardCharsets.UTF_8),
            KnowledgeCsvImportStrategy.CREATE_ONLY);
        KnowledgeCsvPreviewRow row = result.getRows().get(0);

        assertEquals("Q", row.getQuestion());
        assertEquals("A", row.getAnswer());
        assertTrue(row.isActive());
        assertEquals(2, row.getRowNumber());
        try {
            result.getRows().add(row);
            throw new AssertionError("rows should be immutable");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }

    private static KnowledgeCsvPreviewPort previewWithLexicalLimits(int maxRows, int maxColumns, int maxCellBytes) {
        return new CsvKnowledgePreviewUseCase(new ApacheCommonsCsvKnowledgeParser(100_000, maxRows, maxColumns, maxCellBytes), 4000);
    }
    private static void assertLexicalLimitError(KnowledgeCsvPreview result, String marker) {
        assertFileError(result, "csv_limits_exceeded");
        assertFalse(result.getFileErrors().get(0).getMessage().contains(marker));
    }
    private static KnowledgeCsvPreviewPort preview(int maxTokens) {
        return new CsvKnowledgePreviewUseCase(new ApacheCommonsCsvKnowledgeParser(100_000, 100), maxTokens);
    }
    private static void assertFileError(KnowledgeCsvPreview preview, String code) {
        assertEquals(0, preview.getRows().size());
        assertEquals(code, preview.getFileErrors().get(0).getCode());
    }
    private static void assertRowError(KnowledgeCsvPreview result, int rowNumber, String code) {
        KnowledgeCsvPreviewRow row = result.getRows().get(rowNumber - 2);
        assertEquals(rowNumber, row.getRowNumber());
        assertTrue(row.getErrors().stream().anyMatch(error -> code.equals(error.getCode())));
    }
    private static String repeat(String text, int count) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < count; index++) result.append(text);
        return result.toString();
    }
}
