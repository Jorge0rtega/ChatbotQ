package com.chatbotq.knowledge.infrastructure.csv;

import com.chatbotq.knowledge.application.model.KnowledgeCsvParsedFile;
import com.chatbotq.knowledge.application.model.KnowledgeCsvParsedRow;
import com.chatbotq.knowledge.application.model.KnowledgeCsvPreviewError;
import com.chatbotq.knowledge.application.port.KnowledgeCsvParser;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public final class ApacheCommonsCsvKnowledgeParser implements KnowledgeCsvParser {
    private static final int DEFAULT_MAX_COLUMNS = 4;
    private static final int DEFAULT_MAX_CELL_ENCODED_BYTES = 32768;
    private final int maxRawBytes;
    private final int maxDataRows;
    private final int maxColumns;
    private final int maxCellEncodedBytes;

    public ApacheCommonsCsvKnowledgeParser(int maxRawBytes, int maxDataRows) {
        this(maxRawBytes, maxDataRows, DEFAULT_MAX_COLUMNS, DEFAULT_MAX_CELL_ENCODED_BYTES);
    }

    public ApacheCommonsCsvKnowledgeParser(int maxRawBytes, int maxDataRows, int maxColumns, int maxCellEncodedBytes) {
        if (maxRawBytes < 1) throw new IllegalArgumentException("maxRawBytes must be positive");
        if (maxDataRows < 1) throw new IllegalArgumentException("maxDataRows must be positive");
        if (maxColumns < 1) throw new IllegalArgumentException("maxColumns must be positive");
        if (maxCellEncodedBytes < 1) throw new IllegalArgumentException("maxCellEncodedBytes must be positive");
        this.maxRawBytes = maxRawBytes;
        this.maxDataRows = maxDataRows;
        this.maxColumns = maxColumns;
        this.maxCellEncodedBytes = maxCellEncodedBytes;
    }

    @Override
    public KnowledgeCsvParsedFile parse(byte[] csvBytes) {
        if (csvBytes == null) throw new IllegalArgumentException("csvBytes must not be null");
        if (csvBytes.length > maxRawBytes) return fileError("file_too_large");
        final String source;
        try {
            source = decodeUtf8(csvBytes);
        } catch (CharacterCodingException invalid) {
            return fileError("invalid_utf8");
        }
        if (!withinLexicalLimits(csvBytes)) return fileError("csv_limits_exceeded");
        String withoutBom = source.startsWith("\uFEFF") ? source.substring(1) : source;
        try (CSVParser parser = CSVFormat.RFC4180.parse(new StringReader(withoutBom))) {
            Iterator<CSVRecord> records = parser.iterator();
            if (!records.hasNext()) return new KnowledgeCsvParsedFile(Collections.<String>emptyList(),
                Collections.<KnowledgeCsvParsedRow>emptyList(), Collections.<KnowledgeCsvPreviewError>emptyList());
            List<String> headers = values(records.next());
            List<KnowledgeCsvParsedRow> rows = new ArrayList<KnowledgeCsvParsedRow>();
            while (records.hasNext()) {
                CSVRecord record = records.next();
                rows.add(new KnowledgeCsvParsedRow(record.getRecordNumber(), values(record)));
            }
            return new KnowledgeCsvParsedFile(headers, rows, Collections.<KnowledgeCsvPreviewError>emptyList());
        } catch (IOException invalid) {
            return fileError("csv_syntax");
        } catch (UncheckedIOException invalid) {
            return fileError("csv_syntax");
        }
    }

    private boolean withinLexicalLimits(byte[] csvBytes) {
        int columns = 1;
        int cellBytes = 0;
        int records = 0;
        boolean inQuotes = false;
        boolean atFieldStart = true;
        boolean recordHasContent = false;
        for (int index = startsWithUtf8Bom(csvBytes) ? 3 : 0; index < csvBytes.length; index++) {
            byte current = csvBytes[index];
            if (inQuotes) {
                recordHasContent = true;
                if (current == '"' && index + 1 < csvBytes.length && csvBytes[index + 1] == '"') {
                    cellBytes += 2;
                    index++;
                } else {
                    cellBytes++;
                    if (current == '"') inQuotes = false;
                }
                if (cellBytes > maxCellEncodedBytes) return false;
                continue;
            }
            if (current == ',') {
                recordHasContent = true;
                columns++;
                if (columns > maxColumns) return false;
                cellBytes = 0;
                atFieldStart = true;
                continue;
            }
            if (current == '\r' || current == '\n') {
                records++;
                if (records > 1 && records - 1 > maxDataRows) return false;
                columns = 1;
                cellBytes = 0;
                atFieldStart = true;
                recordHasContent = false;
                if (current == '\r' && index + 1 < csvBytes.length && csvBytes[index + 1] == '\n') index++;
                continue;
            }
            recordHasContent = true;
            cellBytes++;
            if (cellBytes > maxCellEncodedBytes) return false;
            if (atFieldStart && current == '"') inQuotes = true;
            atFieldStart = false;
        }
        if (recordHasContent) {
            records++;
            if (records > 1 && records - 1 > maxDataRows) return false;
        }
        return true;
    }

    private static boolean startsWithUtf8Bom(byte[] bytes) {
        return bytes.length >= 3 && bytes[0] == (byte) 0xef && bytes[1] == (byte) 0xbb && bytes[2] == (byte) 0xbf;
    }

    private static String decodeUtf8(byte[] csvBytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(csvBytes)).toString();
    }

    private static List<String> values(CSVRecord record) {
        List<String> values = new ArrayList<String>();
        for (String value : record) values.add(value);
        return values;
    }

    private static KnowledgeCsvParsedFile fileError(String code) {
        return new KnowledgeCsvParsedFile(Collections.<String>emptyList(), Collections.<KnowledgeCsvParsedRow>emptyList(),
            Arrays.asList(new KnowledgeCsvPreviewError(code)));
    }
}
