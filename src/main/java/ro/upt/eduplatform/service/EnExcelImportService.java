package ro.upt.eduplatform.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;
import ro.upt.eduplatform.model.EnResult;
import ro.upt.eduplatform.repository.EnResultRepository;

import javax.xml.parsers.SAXParserFactory;
import java.io.InputStream;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class EnExcelImportService {

    private final EnResultRepository enResultRepository;
    private final AnonymizationService anonymizationService;

    public int importFromExcel(InputStream inputStream, int year) throws Exception {
        log.info("Importing EN {} - direct ZIP stream reading", year);

        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(inputStream)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.equals("xl/sharedStrings.xml") ||
                        name.equals("xl/worksheets/sheet1.xml")) {
                    entries.put(name, zis.readAllBytes());
                }
                zis.closeEntry();
            }
        }

        List<String> sst = new ArrayList<>();
        byte[] sstBytes = entries.get("xl/sharedStrings.xml");
        if (sstBytes != null) {
            SstHandler h = new SstHandler();
            SAXParserFactory.newInstance().newSAXParser()
                    .parse(new java.io.ByteArrayInputStream(sstBytes), h);
            sst = h.getStrings();
            log.info("SharedStrings EN: {} entries found", sst.size());
        }

        byte[] sheetBytes = entries.get("xl/worksheets/sheet1.xml");
        if (sheetBytes == null) throw new Exception("Could not find sheet1.xml in the provided file");

        EnSheetHandler handler = new EnSheetHandler(sst, year, enResultRepository, anonymizationService);
        SAXParserFactory.newInstance().newSAXParser()
                .parse(new java.io.ByteArrayInputStream(sheetBytes), handler);

        int savedCount = handler.getSavedCount();
        log.info("EN {} import finished: {} records saved", year, savedCount);
        return savedCount;
    }

    // ── SharedStrings SAX Handler ─────────────────────────────────────────

    private static class SstHandler extends DefaultHandler {
        private final List<String> strings = new ArrayList<>();
        private StringBuilder current = new StringBuilder();
        private boolean inT = false;

        @Override public void startElement(String u, String l, String q, Attributes a) {
            if ("si".equals(q) || "si".equals(l)) current = new StringBuilder();
            if ("t".equals(q) || "t".equals(l)) inT = true;
        }
        @Override public void endElement(String u, String l, String q) {
            if ("si".equals(q) || "si".equals(l)) strings.add(current.toString());
            if ("t".equals(q) || "t".equals(l)) inT = false;
        }
        @Override public void characters(char[] ch, int s, int len) {
            if (inT) current.append(ch, s, len);
        }
        List<String> getStrings() { return strings; }
    }

    // ── Sheet SAX Handler ─────────────────────────────────────────────────

    private static class EnSheetHandler extends DefaultHandler {
        private final List<String> sst;
        private final int year;
        private final EnResultRepository repo;
        private final AnonymizationService anon;

        private Map<Integer, String> currentRow = new HashMap<>();
        private int currentCol = 0;
        private boolean isString = false;
        private boolean isInlineStr = false;
        private StringBuilder cellValue = new StringBuilder();
        private boolean isHeader = true;

        private final Map<String, Integer> colMap = new HashMap<>();

        private final List<EnResult> batch = new ArrayList<>();
        private final Set<String> batchKeys = new HashSet<>();
        private int savedCount = 0;

        EnSheetHandler(List<String> sst, int year, EnResultRepository repo, AnonymizationService anon) {
            this.sst = sst; this.year = year; this.repo = repo; this.anon = anon;
        }

        @Override
        public void startElement(String uri, String local, String qName, Attributes attrs) {
            String name = local.isEmpty() ? qName : local;
            if ("row".equals(name)) {
                currentRow = new HashMap<>();
            } else if ("c".equals(name)) {
                String ref = attrs.getValue("r");
                if (ref != null) currentCol = colIndexFromRef(ref);
                String t = attrs.getValue("t");
                isString = "s".equals(t);
                isInlineStr = "inlineStr".equals(t);
                cellValue = new StringBuilder();
            } else if ("v".equals(name) || "t".equals(name)) {
                cellValue = new StringBuilder();
            }
        }

        @Override
        public void endElement(String uri, String local, String qName) {
            String name = local.isEmpty() ? qName : local;
            if ("v".equals(name)) {
                String raw = cellValue.toString().trim();
                if (!raw.isEmpty()) {
                    if (isString) {
                        try {
                            int idx = Integer.parseInt(raw);
                            currentRow.put(currentCol, idx < sst.size() ? sst.get(idx) : raw);
                        } catch (NumberFormatException e) {
                            currentRow.put(currentCol, raw);
                        }
                    } else {
                        currentRow.put(currentCol, raw);
                    }
                }
            } else if ("t".equals(name) && isInlineStr) {
                String raw = cellValue.toString().trim();
                if (!raw.isEmpty()) currentRow.put(currentCol, raw);
            } else if ("row".equals(name)) {
                if (isHeader) {
                    buildColMap(currentRow);
                    isHeader = false;
                    return;
                }
                EnResult result = parseRow(currentRow, year);
                if (result != null) {
                    String key = result.getAnonymousId() + "_" + result.getYear();
                    if (!batchKeys.contains(key)) {
                        batchKeys.add(key);
                        batch.add(result);
                        if (batch.size() >= 500) saveBatch();
                    }
                }
            } else if ("sheetData".equals(name)) {
                if (!batch.isEmpty()) saveBatch();
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            cellValue.append(ch, start, length);
        }

        private void buildColMap(Map<Integer, String> headerRow) {
            for (Map.Entry<Integer, String> e : headerRow.entrySet()) {
                if (e.getValue() != null) {
                    String key = e.getValue().trim().toLowerCase().replaceAll("\\s+", " ");
                    colMap.put(key, e.getKey());
                }
            }
            log.info("EN {} - {} columns detected in header", year, colMap.size());
            log.info("  COD: col {}, COD SIIIR: col {}, MEDIA: col {}, NOTA FINALA ROMANA: col {}",
                    colMap.get("cod unic candidat"),
                    colMap.get("cod siiir"),
                    colMap.get("media"),
                    colMap.get("nota finala romana"));
        }

        private String getCol(Map<Integer, String> row, String... names) {
            for (String name : names) {
                Integer idx = colMap.get(name.toLowerCase().trim());
                if (idx != null) {
                    String val = row.get(idx);
                    if (val != null && !val.isEmpty()) return val;
                }
            }
            return null;
        }

        private void saveBatch() {
            try {
                repo.saveAll(batch);
                savedCount += batch.size();
            } catch (Exception e) {
                for (EnResult r : batch) {
                    try {
                        if (!repo.existsByAnonymousIdAndYear(r.getAnonymousId(), r.getYear())) {
                            repo.save(r);
                            savedCount++;
                        }
                    } catch (Exception ex) { /* ignore duplicates */ }
                }
            }
            if (savedCount > 0 && savedCount % 10000 < batch.size()) {
                System.out.println("  EN " + year + ": " + savedCount + " saved");
            }
            batch.clear();
            batchKeys.clear();
        }

        private int colIndexFromRef(String ref) {
            int col = 0;
            for (char c : ref.toCharArray()) {
                if (!Character.isLetter(c)) break;
                col = col * 26 + (Character.toUpperCase(c) - 'A' + 1);
            }
            return col - 1;
        }

        private EnResult parseRow(Map<Integer, String> row, int an) {
            String cod = getCol(row, "cod unic candidat", "cod candidat", "cod");
            if (cod == null || cod.isEmpty()) return null;
            if (cod.endsWith(".0")) cod = cod.substring(0, cod.length() - 2);

            // COD SIIIR poate aparea cu spatiu in fata in unele fisiere
            String codSiiir = getCol(row, "cod siiir", " cod siiir", "siiir", "cod siiir ");

            String environment = getCol(row, "mediu");

            Double romanianGrade  = toDouble(getCol(row,
                    "nota finala romana", "nota finala română",
                    "nota romana", "nota română"));
            Double nativeGrade = toDouble(getCol(row,
                    "nota finala lb materna", "nota finala lb. materna",
                    "nota finala lb maternă",
                    "nota lb materna", "nota limba materna"));
            Double mathGrade   = toDouble(getCol(row,
                    "nota finala matematica", "nota finala matematică",
                    "nota matematica", "nota matematică"));
            Double average       = toDouble(getCol(row, "media"));
            Double averageVIII   = toDouble(getCol(row,
                    "media v-viii", "media generala v-viii",
                    "media v viii", "media cls v-viii"));

            EnResult r = new EnResult();
            r.setAnonymousId(anon.anonymizeName(cod));
            r.setYear(an);
            r.setEnvironment(environment != null ? environment : "NECUNOSCUT");
            r.setCounty(detectCounty(codSiiir));
            r.setRomanianGrade(romanianGrade);
            r.setNativeLanguageGrade(nativeGrade);
            r.setMathematicsGrade(mathGrade);
            r.setAverage(average);
            r.setAverageGradeVIII(averageVIII);
            return r;
        }

        int getSavedCount() { return savedCount; }
    }

    private static String detectCounty(String siiir) {
        if (siiir == null || siiir.trim().length() < 2) return "XX";
        try {
            int cod = Integer.parseInt(siiir.trim().substring(0, 2));
            return switch (cod) {
                case 1  -> "AB"; case 2  -> "AR"; case 3  -> "AG"; case 4  -> "BC";
                case 5  -> "BH"; case 6  -> "BN"; case 7  -> "BT"; case 8  -> "BV";
                case 9  -> "BR"; case 10 -> "B";  case 11 -> "BZ"; case 12 -> "CS";
                case 13 -> "CL"; case 14 -> "CJ"; case 15 -> "CT"; case 16 -> "CV";
                case 17 -> "DB"; case 18 -> "DJ"; case 19 -> "GL"; case 20 -> "GR";
                case 21 -> "GJ"; case 22 -> "HR"; case 23 -> "HD"; case 24 -> "IL";
                case 25 -> "IS"; case 26 -> "IF"; case 27 -> "MM"; case 28 -> "MH";
                case 29 -> "MS"; case 30 -> "NT"; case 31 -> "OT"; case 32 -> "PH";
                case 33 -> "SM"; case 34 -> "SJ"; case 35 -> "SB"; case 36 -> "SV";
                case 37 -> "TR"; case 38 -> "TM"; case 39 -> "TL"; case 40 -> "VS";
                case 41 -> "VL"; case 42 -> "VN";
                default -> "XX";
            };
        } catch (NumberFormatException e) { return "XX"; }
    }

    private static Double toDouble(String val) {
        if (val == null || val.isEmpty()) return null;
        try { return Double.parseDouble(val.replace(",", ".")); }
        catch (NumberFormatException e) { return null; }
    }
}