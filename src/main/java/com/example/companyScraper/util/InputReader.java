package com.example.companyScraper.util;

import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class InputReader {

    public List<String> readUrls(MultipartFile file) throws Exception {
        String filename = file.getOriginalFilename().toLowerCase();
        if (filename.endsWith(".csv") || filename.endsWith(".txt")) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
                return br.lines()
                        .map(String::trim)
                        .filter(line -> !line.isEmpty())
                        .map(line -> line.startsWith("http") ? line : "https://" + line)
                        .collect(Collectors.toList());
            }
        } else if (filename.endsWith(".xlsx")) {
            List<String> urls = new ArrayList<>();
            try (InputStream is = file.getInputStream(); Workbook workbook = WorkbookFactory.create(is)) {
                for (Sheet sheet : workbook) {
                    for (Row row : sheet) {
                        for (Cell cell : row) {
                            if (cell.getCellType() == CellType.STRING) {
                                String val = cell.getStringCellValue().trim();
                                if (!val.isEmpty()) {
                                    urls.add(val.startsWith("http") ? val : "https://" + val);
                                }
                            }
                        }
                    }
                }
            }
            return urls;
        } else {
            throw new IllegalArgumentException("Unsupported file format. Only CSV, TXT, XLSX supported.");
        }
    }
}
