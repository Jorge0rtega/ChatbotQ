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
    private final int maxRawBytes;
    private final int maxDataRows;

    public ApacheCommonsCsvKnowledgeParser(int maxRawBytes, int maxDataRows) {
        if (maxRawBytes < 1) throw new IllegalArgumentException("maxRawBytes must be positive");
        if (maxDataRows < 1) throw new IllegalArgumentException("maxDataRows must be positive");
        this.maxRawBytes = maxRawBytes;
        this.maxDataRows = maxDataRows;
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
        String withoutBom = source.startsWith("\uFEFF") ? source.substring(1) : source;
        try (CSVParser parser = CSVFormat.RFC4180.parse(new StringReader(withoutBom))) {
            Iterator<CSVRecord> records = parser.iterator();
            if (!records.hasNext()) return new KnowledgeCsvParsedFile(Collections.<String>emptyList(),
                Collections.<KnowledgeCsvParsedRow>emptyList(), Collections.<KnowledgeCsvPreviewError>emptyList());
            List<String> headers = values(records.next());
            List<KnowledgeCsvParsedRow> rows = new ArrayList<KnowledgeCsvParsedRow>();
            while (records.hasNext()) {
                if (rows.size() >= maxDataRows) return fileError("too_many_rows");
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
