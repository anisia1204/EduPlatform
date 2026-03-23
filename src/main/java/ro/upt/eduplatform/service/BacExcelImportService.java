package ro.upt.eduplatform.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;
import ro.upt.eduplatform.model.BacResult;
import ro.upt.eduplatform.repository.BacResultRepository;

import javax.xml.parsers.SAXParserFactory;
import java.io.InputStream;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;


@Slf4j
@Service
@RequiredArgsConstructor
public class BacExcelImportService {

    private final BacResultRepository bacResultRepository;
    private final AnonymizationService anonymizationService;

    public int importFromExcel(InputStream inputStream, int year) throws Exception {
        log.info("Importing BAC {} - direct ZIP reading", year);

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

        List<String> sharedStrings = new ArrayList<>();
        byte[] sstBytes = entries.get("xl/sharedStrings.xml");
        if (sstBytes != null) {
            SstHandler sstHandler = new SstHandler();
            SAXParserFactory.newInstance().newSAXParser()
                    .parse(new java.io.ByteArrayInputStream(sstBytes), sstHandler);
            sharedStrings = sstHandler.getStrings();
            log.info("SharedStrings: {} entries", sharedStrings.size());
        }

        byte[] sheetBytes = entries.get("xl/worksheets/sheet1.xml");
        if (sheetBytes == null) throw new Exception("Could not find sheet1.xml in file");

        SheetHandler sheetHandler = new SheetHandler(sharedStrings, year,
                bacResultRepository, anonymizationService);
        SAXParserFactory.newInstance().newSAXParser()
                .parse(new java.io.ByteArrayInputStream(sheetBytes), sheetHandler);

        int savedCount = sheetHandler.getSavedCount();
        log.info("BAC {} import completed: {} records saved", year, savedCount);
        return savedCount;
    }

    // ── SharedStrings SAX Handler ─────────────────────────────────────────

    private static class SstHandler extends DefaultHandler {
        private final List<String> strings = new ArrayList<>();
        private StringBuilder current = new StringBuilder();
        private boolean inT = false;

        @Override public void startElement(String uri, String local, String qName, Attributes attrs) {
            if ("si".equals(qName) || "si".equals(local)) current = new StringBuilder();
            if ("t".equals(qName) || "t".equals(local)) inT = true;
        }
        @Override public void endElement(String uri, String local, String qName) {
            if ("si".equals(qName) || "si".equals(local)) strings.add(current.toString());
            if ("t".equals(qName) || "t".equals(local)) inT = false;
        }
        @Override public void characters(char[] ch, int start, int length) {
            if (inT) current.append(ch, start, length);
        }
        List<String> getStrings() { return strings; }
    }

    // ── Sheet SAX Handler ─────────────────────────────────────────────────

    private static class SheetHandler extends DefaultHandler {
        private final List<String> sst;
        private final int year;
        private final BacResultRepository repo;
        private final AnonymizationService anon;

        private Map<Integer, String> currentRow = new HashMap<>();
        private int currentRowNum = 0;
        private int currentCol = 0;
        private boolean isString = false;
        private boolean isInlineStr = false;
        private StringBuilder cellValue = new StringBuilder();
        private boolean isHeader = true;

        private final Map<String, Integer> colMap = new HashMap<>();
        private boolean isFormat2022 = false;

        private final List<BacResult> batch = new ArrayList<>();
        private final Set<String> batchKeys = new HashSet<>();
        private int savedCount = 0;

        SheetHandler(List<String> sst, int year, BacResultRepository repo, AnonymizationService anon) {
            this.sst = sst; this.year = year; this.repo = repo; this.anon = anon;
        }

        @Override
        public void startElement(String uri, String local, String qName, Attributes attrs) {
            String name = local.isEmpty() ? qName : local;
            if ("row".equals(name)) {
                currentRow = new HashMap<>();
                String r = attrs.getValue("r");
                currentRowNum = r != null ? Integer.parseInt(r) : currentRowNum + 1;
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
                BacResult result = isFormat2022 ? parseRow2022(currentRow) : parseRowStandard(currentRow);
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
            // Format 2022: are coloana "judet" ca prima coloana si "status_final"/"media_finala"
            isFormat2022 = colMap.containsKey("status_final") && colMap.containsKey("media_finala");
            log.info("BAC {} - {} coloane, format: {}", year, colMap.size(),
                    isFormat2022 ? "2022 (extended)" : "standard");
            if (isFormat2022) {
                log.info("  COD: col {}, STATUS_FINAL: col {}, MEDIA_FINALA: col {}, JUDET: col {}",
                        colMap.get("cod"), colMap.get("status_final"),
                        colMap.get("media_finala"), colMap.get("judet"));
            } else {
                log.info("  COD: col {}, STATUS: col {}, MEDIE: col {}, SIIIR: col {}",
                        colMap.get("cod unic candidat"), colMap.get("status"),
                        colMap.get("medie"), colMap.get("unitate (siiir)"));
            }
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

        private BacResult parseRowStandard(Map<Integer, String> row) {
            String cod = getCol(row, "cod unic candidat");
            if (cod == null || cod.isEmpty()) return null;
            if (cod.endsWith(".0")) cod = cod.substring(0, cod.length() - 2);

            String specialization = getCol(row, "specializare");
            String profile        = getCol(row, "profil");
            String track          = getCol(row, "fileira", "filiera");
            String environment    = getCol(row, "mediu candidat", "mediu");
            String siiirUnit      = getCol(row, "unitate (siiir)", "unitate(siiir)");
            Double romanianGrade  = toDouble(getCol(row, "nota_ea"));
            Double mandatoryGrade = toDouble(getCol(row, "nota_ec"));
            Double electiveGrade  = toDouble(getCol(row, "nota_ed"));
            String status         = getCol(row, "status");
            Double average        = toDouble(getCol(row, "medie", "media"));

            if (status == null || status.isEmpty()) return null;

            return buildResult(cod, specialization, profile, track, environment,
                    siiirUnit, romanianGrade, mandatoryGrade, electiveGrade,
                    status, average, detectCounty(siiirUnit));
        }

        private BacResult parseRow2022(Map<Integer, String> row) {
            String cod = getCol(row, "cod");
            if (cod == null || cod.isEmpty()) return null;
            if (cod.endsWith(".0")) cod = cod.substring(0, cod.length() - 2);

            String county         = getCol(row, "judet");
            String siiirCode      = getCol(row, "cod siiir");
            String environment    = getCol(row, "mediu");
            String specialization = getCol(row, "specializare");
            String profile        = getCol(row, "profil");
            String track          = getCol(row, "filiera");
            Double romanianGrade  = toDouble(getCol(row, "nota_ea"));
            Double mandatoryGrade = toDouble(getCol(row, "nota_ec"));
            Double electiveGrade  = toDouble(getCol(row, "nota_ed"));
            String status         = getCol(row, "status_final");
            Double average        = toDouble(getCol(row, "media_finala"));

            if (status == null || status.isEmpty()) return null;

            String finalCounty = (county != null && !county.isEmpty()) ? county.trim().toUpperCase() : detectCounty(siiirCode);

            return buildResult(cod, specialization, profile, track, environment,
                    siiirCode, romanianGrade, mandatoryGrade, electiveGrade,
                    status, average, finalCounty);
        }

        private BacResult buildResult(String code, String specialization, String profile,
                                      String track, String environment, String siiirUnit,
                                      Double romanianGrade, Double mandatoryGrade, Double electiveGrade,
                                      String status, Double average, String county) {

            String fullProfile = (track != null ? track + " - " : "") +
                    (profile  != null ? profile  + " - " : "") +
                    (specialization != null ? specialization : "");
            if (fullProfile.length() > 300) fullProfile = fullProfile.substring(0, 300);

            boolean passed = status.toUpperCase().contains("PROMOVAT")
                    && !status.toUpperCase().contains("NEPROMOVAT");

            BacResult result = new BacResult();
            result.setAnonymousId(anon.anonymizeName(code));
            result.setYear(year);
            result.setCounty(county != null ? county : "XX");
            result.setSchoolUnit(siiirUnit != null ? siiirUnit : "");
            result.setProfile(fullProfile);
            result.setGeneralAverage(average);
            result.setRomanianGrade(romanianGrade);
            result.setMandatorySubjectGrade(mandatoryGrade);
            result.setElectiveSubjectGrade(electiveGrade);
            result.setIsPassed(passed);
            result.setRawResult(status);
            result.setAverageCategory(categorizeAverage(average));
            result.setEnvironment(environment != null ? environment : "UNKNOWN");
            return result;
        }

        private void saveBatch() {
            try {
                repo.saveAll(batch);
                savedCount += batch.size();
            } catch (Exception e) {
                // Daca saveAll esueaza din cauza duplicatelor, salvam individual
                for (BacResult r : batch) {
                    try {
                        if (!repo.existsByAnonymousIdAndYear(r.getAnonymousId(), r.getYear())) {
                            repo.save(r);
                            savedCount++;
                        }
                    } catch (Exception ex) { /* ignore */ }
                }
            }
            if (savedCount > 0 && savedCount % 10000 < batch.size()) {
                System.out.println("  BAC " + year + ": " + savedCount + " saved");
            }
            batch.clear();
            batchKeys.clear();
        }

        private int colIndexFromRef(String cellRef) {
            int col = 0;
            for (char c : cellRef.toCharArray()) {
                if (!Character.isLetter(c)) break;
                col = col * 26 + (Character.toUpperCase(c) - 'A' + 1);
            }
            return col - 1;
        }

        int getSavedCount() { return savedCount; }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

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

    private static String categorizeAverage(Double average) {
        if (average == null) return "NECUNOSCUT";
        if (average >= 9.5) return "EXCELENT";
        if (average >= 8.0) return "FOARTE_BINE";
        if (average >= 7.0) return "BINE";
        if (average >= 6.0) return "SATISFACATOR";
        if (average >= 5.0) return "SUFICIENT";
        return "INSUFICIENT";
    }

    private static Double toDouble(String val) {
        if (val == null || val.isEmpty()) return null;
        try { return Double.parseDouble(val.replace(",", ".")); }
        catch (NumberFormatException e) { return null; }
    }
}