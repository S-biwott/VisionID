package com.example.OcrTesting.controller;

import com.example.OcrTesting.model.OcrTestingModel;
import com.example.OcrTesting.service.OcrTestingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_videoio.VideoCapture;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.bytedeco.opencv.global.opencv_imgcodecs.imwrite;

@Slf4j
@CrossOrigin(origins = "http://localhost:4200")
@RestController
@RequestMapping("/api/ocr")
@RequiredArgsConstructor
public class OcrTestingController {

    private final OcrTestingService ocrService;

    // Month name → number map (handles OCR-garbled month names like "@9 OCT 1992")
    private static final Map<String, String> MONTH_MAP = Map.ofEntries(
            Map.entry("JAN", "01"), Map.entry("FEB", "02"), Map.entry("MAR", "03"),
            Map.entry("APR", "04"), Map.entry("MAY", "05"), Map.entry("JUN", "06"),
            Map.entry("JUL", "07"), Map.entry("AUG", "08"), Map.entry("SEP", "09"),
            Map.entry("OCT", "10"), Map.entry("NOV", "11"), Map.entry("DEC", "12")
    );

    @GetMapping("/test")
    public String test() {
        return "OCR API is running";
    }

    @PostMapping("/upload-id")
    public ResponseEntity<?> uploadId(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty())
            return ResponseEntity.badRequest().body("File is empty");

        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/"))
            return ResponseEntity.badRequest().body("Only image files are accepted");

        File tempFile = null;
        try {
            tempFile = File.createTempFile("id-upload-", ".img");
            file.transferTo(tempFile);

            OcrTestingModel model = ocrService.extractText(tempFile);
            parseIdDetails(model);
            return ResponseEntity.ok(model);

        } catch (Exception e) {
            log.error("Failed to process uploaded ID", e);
            return ResponseEntity.internalServerError()
                    .body("OCR processing failed: " + e.getMessage());
        } finally {
            deleteSilently(tempFile);
        }
    }

    @GetMapping("/scan-camera")
    public ResponseEntity<?> scanCamera() {
        VideoCapture camera = new VideoCapture(0);
        if (!camera.isOpened())
            return ResponseEntity.internalServerError().body("Cannot access camera");

        File tempFile = null;
        try {
            Mat frame = new Mat();
            long deadline = System.currentTimeMillis() + 10_000;

            while (System.currentTimeMillis() < deadline) {
                camera.read(frame);
                if (!frame.empty()) {
                    tempFile = File.createTempFile("camera-id-", ".png");
                    imwrite(tempFile.getAbsolutePath(), frame);

                    OcrTestingModel model = ocrService.extractText(tempFile);
                    parseIdDetails(model);
                    return ResponseEntity.ok(model);
                }
                Thread.sleep(50);
            }
            return ResponseEntity.internalServerError()
                    .body("Timed out waiting for a frame from the camera");

        } catch (Exception e) {
            log.error("Camera scan failed", e);
            return ResponseEntity.internalServerError()
                    .body("Camera scan failed: " + e.getMessage());
        } finally {
            camera.release();
            deleteSilently(tempFile);
        }
    }

    // Parsing orchestration STARTS HERE
    private void parseIdDetails(OcrTestingModel model) {
        String text = model.getRawText();
        if (text == null || text.isBlank()) return;

        DocumentType type = detectDocumentType(text);
        log.debug("Detected document type: {}", type);

        model.setDocumentType(type.name());

        switch (type) {
            case PASSPORT       -> parsePassport(text, model);
            case DRIVER_LICENSE -> parseDriverLicense(text, model);
            case NATIONAL_ID    -> parseNationalId(text, model);
            default             -> parseFallback(text, model);
        }
    }

    // Document-type detection
    // Deliberately broad — OCR garbles many header words so we check multiple known variants and sub-strings for each document type.
    private enum DocumentType { PASSPORT, DRIVER_LICENSE, NATIONAL_ID, UNKNOWN }

    private DocumentType detectDocumentType(String text) {
        String upper = text.toUpperCase();

        // --- PASSPORT ---
        if (upper.contains("PASSPORT") || upper.contains("PASSEPORT") ||
                upper.contains("PASI")     || upper.contains("PASEPORT"))
            return DocumentType.PASSPORT;
        // --- DRIVER'S LICENCE ---
        if (upper.contains("DRIVING") || upper.contains("LICENCE") || upper.contains("LICENSE"))
            return DocumentType.DRIVER_LICENSE;
        // --- NATIONAL ID ---
        if (upper.contains("JAMHURI")     || upper.contains("SASANURI") ||
                upper.contains("KITAMBULISHO")|| upper.contains("IDENTITY") ||
                upper.contains("ID NUMB")     || upper.contains("ID NUM")   ||
                upper.contains("NUMIE")       || upper.contains("NOME")     ||
                upper.contains("ID NO"))
            return DocumentType.NATIONAL_ID;

        return DocumentType.UNKNOWN;
    }

    // Passport parser
    // DOB now handles "DD MON YYYY" format e.g. "@9 OCT 1992"
    private void parsePassport(String text, OcrTestingModel model) {
        // Name: labelled → positional → MRZ (last resort)
        model.setFullName(firstNonNull(
                extractLabelledName(text),
                extractPassportNamePositional(text),
                extractNameFromMrz(text)
        ));

        // Passport number — Kenyan passports: letter(s)+digits OR digit+letter+digits e.g. A1234567  AB123456
        // Stored in passportNumber, NOT idNumber (passports have no National ID) idNumber explicitly left null for passports
        model.setPassportNumber(firstNonNull(
                extractLabelled(text,"PASSPORT\\s*NO\\.?|DOCUMENT\\s*NO\\.?","[A-Z0-9]{6,10}"), extractPassportNumber(text)
        ));

        model.setDateOfBirth(extractDateOfBirth(text));
    }

    private String extractPassportNumber(String text) {
        // Match from non-MRZ lines only to avoid false positives
        for (String line : text.split("\\r?\\n")) {
            if (line.contains("<<")) continue; // MRZ line
            Matcher m = Pattern.compile("\\b([A-Z0-9]{1,2}[A-Z0-9]{5,8})\\b").matcher(line);
            while (m.find()) {
                String candidate = m.group(1);
                // Must contain at least one letter and at least 5 digits
                if (candidate.matches(".*[A-Z].*") && candidate.matches(".*\\d{5,}.*"))
                    return candidate;
            }
        }
        return null;
    }

    private String extractPassportNamePositional(String text) {
        String[] lines = text.split("\\r?\\n");
        List<String> candidates = new java.util.ArrayList<>();
        boolean pastHeader = false;

        for (String line : lines) {
            String trimmed = line.trim();
            String upper   = trimmed.toUpperCase();

            if (upper.contains("PASSPORT") || upper.contains("PASI") || upper.contains("PASSEPORT")) {
                pastHeader = true;
                continue;
            }
            if (!pastHeader) continue;

            // All-caps, letters+spaces only, 2–35 chars, not a header phrase
            if (trimmed.matches("[A-Z][A-Z\\s]{1,34}") && !isHeaderPhrase(trimmed))
                candidates.add(trimmed);
        }

        if (candidates.isEmpty()) return null;
        if (candidates.size() == 1) return candidates.get(0);
        // Kenyan passport layout: line 1 = surname, line 2 = given names
        return (candidates.get(1) + " " + candidates.get(0)).trim();
    }

    /** MRZ fallback: P&lt;KENsurname&lt;&lt;givennames */
    private String extractNameFromMrz(String text) {
        Matcher m = Pattern.compile("P<[A-Z]{3}([A-Z]+)<<([A-Z<]+)").matcher(text);
        if (!m.find()) return null;
        String surname    = m.group(1);
        String givenNames = m.group(2).replace("<", " ").trim();
        return (givenNames + " " + surname).trim();
    }

    // Driver's licence parser
    private void parseDriverLicense(String text, OcrTestingModel model) {
        String[] lines = text.split("\\r?\\n");

        model.setFullName(extractDriverName(lines));
        model.setIdNumber(extractDriverIdNumber(lines, text));
        model.setDateOfBirth(extractDateOfBirth(text));
    }

    private String extractDriverName(String[] lines) {
        String surname    = null;
        String otherNames = null;

        for (int i = 0; i < lines.length; i++) {
            String raw   = lines[i];
            String upper = raw.toUpperCase();
            // Strip non-alpha for safe keyword checks
            String alphaOnly = upper.replaceAll("[^A-Z\\s]", " ").trim();

            boolean isSurnameLine =
                    alphaOnly.contains("SUR") &&
                            !alphaOnly.contains("OTHER") &&
                            !alphaOnly.contains("GIVEN") &&
                            // Exclude lines that are purely about "NATIONAL" (no SUR prefix token)
                            !(alphaOnly.trim().startsWith("NATIONAL"));

            if (isSurnameLine) {
                String val = cleanNameFromLine(valueLineBelow(lines, i));
                if (val != null) surname = val;
                log.debug("Driver surname candidate: '{}'", val);
            }

            // OTHER NAMES / GIVEN NAMES label row
            if ((alphaOnly.contains("OTHER") && alphaOnly.contains("NAME")) ||
                    (alphaOnly.contains("GIVEN") && alphaOnly.contains("NAME"))) {
                String val = cleanNameFromLine(valueLineBelow(lines, i));
                if (val != null) otherNames = val;
                log.debug("Driver otherNames candidate: '{}'", val);
            }
        }

        if (surname != null && otherNames != null) return (otherNames + " " + surname).trim();
        if (surname    != null) return surname;
        if (otherNames != null) return otherNames;
        return longestNameLine(lines);
    }

    private String valueLineBelow(String[] lines, int idx) {
        for (int j = idx + 1; j < Math.min(lines.length, idx + 4); j++) {
            String next = lines[j].trim();
            if (!next.isBlank() && next.matches(".*[A-Za-z].*")) return next;
        }
        return null;
    }

    private String cleanNameFromLine(String line) {
        if (line == null) return null;

        List<String> tokens = new java.util.ArrayList<>();
        for (String token : line.split("\\s+")) {
            // Accept purely alpha OR hyphenated alpha tokens, minimum 2 chars
            if (token.matches("[A-Za-z]{2,}|[A-Za-z]+(?:-[A-Za-z]+)+") &&
                    !isHeaderWord(token.toUpperCase())) {
                tokens.add(token.toUpperCase());
            }
        }
        if (tokens.isEmpty()) return null;

        // Strip trailing noise tokens (≤3 chars: WTX, ME, KE, etc.)
        while (!tokens.isEmpty() && tokens.get(tokens.size() - 1).length() <= 3) {
            tokens.remove(tokens.size() - 1);
        }
        if (tokens.isEmpty()) return null;

        return String.join(" ", tokens);
    }

    /** True if a single uppercase token is a known document / label word. */
    private boolean isHeaderWord(String token) {
        return HEADER_WORDS.stream().anyMatch(hw -> hw.equals(token));
    }

    private String extractDriverIdNumber(String[] lines, String fullText) {
        for (int i = 0; i < lines.length; i++) {
            String upper = lines[i].toUpperCase();
            // "NATIONAL ID NO" — "IO" is a common OCR misread of "ID"
            if (upper.contains("NATIONAL") && (upper.contains("ID") || upper.contains("IO"))) {
                // Number may be on this line or the next
                Matcher m = Pattern.compile("\\b(\\d{7,9})\\b").matcher(lines[i]);
                if (m.find()) return m.group(1);
                if (i + 1 < lines.length) {
                    m = Pattern.compile("\\b(\\d{7,9})\\b").matcher(lines[i + 1]);
                    if (m.find()) return m.group(1);
                }
            }
        }
        // Bare 8-digit Kenyan National ID number
        Matcher m = Pattern.compile("\\b(\\d{8})\\b").matcher(fullText);
        return m.find() ? m.group(1) : null;
    }

    // National ID parser
    private void parseNationalId(String text, OcrTestingModel model) {
        String[] lines = text.split("\\r?\\n");
        model.setFullName(extractNationalIdName(lines, text));
        model.setIdNumber(extractNationalIdNumber(lines, text));
        model.setDateOfBirth(extractDateOfBirth(text));
    }

    private String extractNationalIdName(String[] lines, String fullText) {

        String labelled = extractLabelledName(fullText);
        if (labelled != null) return labelled;

        String surname    = null;
        String givenNames = null;

        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            // Must be a single all-caps word (no spaces, no digits), 3–20 chars
            if (!t.matches("[A-Z]{3,20}") || isHeaderPhrase(t) || isIgnoredToken(t)) continue;

            if (surname == null) {
                surname = t;
            } else if (givenNames == null) {
                givenNames = t;
                break;
            }
        }

        if (surname != null && givenNames != null) return (givenNames + " " + surname).trim();
        if (surname    != null) return surname;

        return longestNameLine(lines);
    }

    private boolean isIgnoredToken(String token) {
        return token.equals("MALE")   || token.equals("FEMALE") ||
                token.equals("KEN")    || token.equals("ALA")    ||
                token.equals("KENYAN") || token.equals("BATA")   ||
                token.length() <= 2;
    }

    private String extractNationalIdNumber(String[] lines, String fullText) {
        String bestId       = null;
        int    bestPriority = -1;

        for (int i = 0; i < lines.length; i++) {
            String upper = lines[i].toUpperCase();

            // Explicitly skip pure SERIAL NUMBER lines (no ID label present)
            if (upper.contains("SERIAL") &&
                    !upper.contains("ID NUM") && !upper.contains("ID NO") &&
                    !upper.contains("NUMIE")  && !upper.contains("NOME"))
                continue;

            boolean isIdLabel =
                    upper.contains("ID NUMBER") || upper.contains("ID NUM") ||
                            upper.contains("ID NO")     ||
                            upper.contains("NUMIE")     ||   // "NOME 251914816 ... NUMIE 39891331"
                            upper.contains("NUMB")      ||
                            upper.trim().equals("ID");       // bare "ID" label line (some formats)

            if (!isIdLabel) continue;

            // --- Collect numbers on this line, skipping SERIAL-labelled ones ---
            List<String> sameLineNums = new java.util.ArrayList<>();
            Matcher m = Pattern.compile("\\b(\\d{5,13})\\b").matcher(lines[i]);
            while (m.find()) {
                String n   = m.group(1);
                int    pos = lines[i].indexOf(n);
                String before = lines[i].substring(0, pos).toUpperCase();
                // Skip number if the only label before it is SERIAL/NOME (not ID/NUMIE)
                boolean onlySerialBefore =
                        (before.contains("SERIAL") || before.contains("NOME")) &&
                                !before.contains("ID NUM") && !before.contains("NUMIE");
                if (onlySerialBefore) continue;
                sameLineNums.add(n);
            }
            if (!sameLineNums.isEmpty()) {
                // Last number = real ID (serial / NOME number always appears first)
                String candidate = sameLineNums.get(sameLineNums.size() - 1);
                int priority = candidate.length();
                if (priority > bestPriority) { bestPriority = priority; bestId = candidate; }
            }

            // --- Try next line (Maisha layout: label alone, value below) ---
            if (i + 1 < lines.length) {
                // Dedicated number line (only digits + whitespace)
                m = Pattern.compile("^\\s*(\\d{5,13})\\s*$").matcher(lines[i + 1]);
                if (m.find()) {
                    String candidate = m.group(1);
                    int priority = candidate.length() + 5; // bonus for dedicated value line
                    if (priority > bestPriority) { bestPriority = priority; bestId = candidate; }
                }
                // Number embedded in noisy next line
                m = Pattern.compile("\\b(\\d{5,13})\\b").matcher(lines[i + 1]);
                if (m.find()) {
                    String candidate = m.group(1);
                    int priority = candidate.length() + 3;
                    if (priority > bestPriority) { bestPriority = priority; bestId = candidate; }
                }
            }
        }

        if (bestId != null) return bestId;

        // Bare fallback — prefer longer numbers, minimum 7 digits to avoid date fragments
        Matcher m = Pattern.compile("\\b(\\d{7,13})\\b").matcher(fullText);
        String longest = null;
        while (m.find()) {
            String n = m.group(1);
            if (longest == null || n.length() > longest.length()) longest = n;
        }
        // If nothing ≥7, accept 5–6 digit partial reads (truncated OCR)
        if (longest == null) {
            m = Pattern.compile("\\b(\\d{5,6})\\b").matcher(fullText);
            while (m.find()) {
                String n = m.group(1);
                if (longest == null || n.length() > longest.length()) longest = n;
            }
        }
        return longest;
    }

    private void parseFallback(String text, OcrTestingModel model) {
        String[] lines = text.split("\\r?\\n");
        model.setFullName(firstNonNull(extractLabelledName(text), longestNameLine(lines)));
        model.setIdNumber(firstNonNull(
                extractLabelled(text, "ID\\s*NUMBER|ID\\.?\\s*NO\\.?|NATIONAL\\s*ID", "\\d{7,13}"),
                extractPattern(text, "\\b\\d{8}\\b")
        ));
        model.setDateOfBirth(extractDateOfBirth(text));
    }

    // Shared field extractors Extracts a full name from labelled fields, handling all Kenyan document variants:
    private String extractLabelledName(String text) {
        final String NAME_VAL = "[A-Z][A-Z\\s\\-]*";
        String[] lines = text.split("\\r?\\n");

        String surname    = null;
        String givenNames = null;

        for (int i = 0; i < lines.length; i++) {
            String line  = lines[i];
            String upper = line.trim().toUpperCase();

            // --- FULL NAMES (old Kenyan ID): same line ---
            if (upper.contains("FULL") && upper.contains("NAME")) {
                Matcher m = Pattern.compile("(?i)FULL\\s*NAMES?[:\\s]+(" + NAME_VAL + ")")
                        .matcher(line);
                if (m.find()) return m.group(1).trim();
            }

            // --- SURNAME: same line OR next line ---
            if (upper.contains("SURNAME")) {
                Matcher m = Pattern.compile("(?i)SURNAME[:\\s]+(" + NAME_VAL + ")")
                        .matcher(line);
                if (m.find()) {
                    surname = m.group(1).trim();
                } else if (i + 1 < lines.length) {
                    // Maisha layout: label alone on this line, value on next
                    String next = lines[i + 1].trim();
                    if (next.matches("[A-Z][A-Z\\s\\-]{1,39}") && !isHeaderPhrase(next))
                        surname = next;
                }
            }

            // --- GIVEN NAME / GIVEN NAMES: same line OR next line ---
            if (upper.contains("GIVEN") && upper.contains("NAME")) {
                Matcher m = Pattern.compile("(?i)GIVEN\\s*NAMES?[:\\s]+(" + NAME_VAL + ")")
                        .matcher(line);
                if (m.find()) {
                    givenNames = m.group(1).trim();
                } else if (i + 1 < lines.length) {
                    String next = lines[i + 1].trim();
                    if (next.matches("[A-Z][A-Z\\s\\-]{1,39}") && !isHeaderPhrase(next))
                        givenNames = next;
                }
            }

            // --- OTHER NAMES (driver's licence): same line OR next line ---
            if (upper.contains("OTHER") && upper.contains("NAME")) {
                Matcher m = Pattern.compile("(?i)OTHER\\s*NAMES?[:\\s]+(" + NAME_VAL + ")")
                        .matcher(line);
                if (m.find()) {
                    givenNames = m.group(1).trim();
                } else if (i + 1 < lines.length) {
                    String next = lines[i + 1].trim();
                    if (next.matches("[A-Z][A-Z\\s\\-]{1,39}") && !isHeaderPhrase(next))
                        givenNames = next;
                }
            }

            // --- Generic NAME: label, last resort ---
            if (surname == null && givenNames == null &&
                    upper.contains("NAME") &&
                    !upper.contains("SURNAME") && !upper.contains("GIVEN") &&
                    !upper.contains("OTHER")  && !upper.contains("FULL")) {
                Matcher m = Pattern.compile("(?i)\\bNAME[:\\s]+(" + NAME_VAL + ")")
                        .matcher(line);
                if (m.find()) return m.group(1).trim();
            }
        }

        if (surname != null && givenNames != null) return (givenNames + " " + surname).trim();
        if (surname    != null) return surname;
        if (givenNames != null) return givenNames;
        return null;
    }

    private String extractLabelled(String text, String labelPattern, String valuePattern) {
        Matcher m = Pattern.compile(
                        "(?i)(?:" + labelPattern + ")[:\\s\\-]*(" + valuePattern + ")")
                .matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private String extractPattern(String text, String pattern) {
        Matcher m = Pattern.compile(pattern).matcher(text);
        return m.find() ? m.group() : null;
    }
//    Extracts and normalises a date of birth to DD/MM/YYYY. Handles ALL formats present
    private String extractDateOfBirth(String text) {
        // Any single-char separator OR spaces around a separator
        final String SEP = "\\s*[.\\-/:]\\s*|\\s+";

        Matcher m = Pattern.compile(
                        "(?i)(DATE\\s*OF\\s*(?:BIRTH|GIRTH)|D\\.?O\\.?B)" +
                                "[:\\s\\-]*" +
                                "(\\d{1,2})(?:" + SEP + ")(\\d{1,2})(?:" + SEP + ")(\\d{2,4})")
                .matcher(text);
        if (m.find()) return buildDate(m.group(2), m.group(3), m.group(4));

        m = Pattern.compile(
                        "(\\d{1,2})(?:" + SEP + ")(\\d{1,2})(?:" + SEP + ")((19|20)\\d{2})")
                .matcher(text);
        String bestDate = null;
        int    bestYear = Integer.MAX_VALUE;
        while (m.find()) {
            try {
                int day   = Integer.parseInt(m.group(1).trim());
                int month = Integer.parseInt(m.group(2).trim());
                int year  = Integer.parseInt(m.group(3).trim());
                if (day >= 1 && day <= 31 && month >= 1 && month <= 12
                        && year >= 1900 && year <= 2015 && year < bestYear) {
                    bestYear = year;
                    bestDate = buildDate(m.group(1), m.group(2), m.group(3));
                }
            } catch (NumberFormatException ignored) {}
        }
        if (bestDate != null) return bestDate;

        m = Pattern.compile(
                        "(?<![A-Z0-9])(\\d{1,2})\\s+(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)\\s+(\\d{4})",
                        Pattern.CASE_INSENSITIVE)
                .matcher(text);
        if (m.find()) {
            String day   = String.format("%02d", Integer.parseInt(m.group(1)));
            String month = MONTH_MAP.get(m.group(2).toUpperCase());
            return day + "/" + month + "/" + m.group(3);
        }

        return null;
    }

    private String buildDate(String day, String month, String year) {
        String d  = String.format("%02d", Integer.parseInt(day.trim()));
        String mo = String.format("%02d", Integer.parseInt(month.trim()));
        String yr = year.trim().length() == 2 ? "19" + year.trim() : year.trim();
        return d + "/" + mo + "/" + yr;
    }

    private String longestNameLine(String[] lines) {
        String best      = null;
        int    bestScore = 0;

        for (String line : lines) {
            String trimmed = line.trim();
            // Allow: uppercase letters, spaces, hyphens
            if (!trimmed.matches("[A-Z][A-Z\\s\\-]{3,}")) continue;

            String[] tokens = trimmed.split("[\\s\\-]+");
            if (tokens.length < 2) continue;
            if (isHeaderPhrase(trimmed)) continue;

            int score = trimmed.length();
            if (score > bestScore) {
                bestScore = score;
                best      = trimmed;
            }
        }
        return best;
    }

    private static final List<String> HEADER_WORDS = List.of(
            "REPUBLIC", "KENYA", "JAMHURI", "SASANURI", "REPUBLIQUE",
            "PASSPORT", "PASSEPORT", "PASI", "PASEPORT",
            "DRIVING",  "LICENCE",   "LICENSE",
            "NATIONAL", "IDENTITY",  "KITAMBULISHO", "TAIFA",
            "GOVERNMENT", "IMMIGRATION",
            "FEMALE", "MALE", "NAIROBI", "MAKADARA",
            "SERIAL",  "MAISHA",    "HOLDER"   // Maisha card / old ID noise words
    );

    private boolean isHeaderPhrase(String line) {
        String upper = line.toUpperCase();
        for (String word : HEADER_WORDS)
            if (upper.contains(word)) return true;
        return false;
    }

    // Utilities
    @SafeVarargs
    private <T> T firstNonNull(T... values) {
        for (T v : values) if (v != null) return v;
        return null;
    }

    private void deleteSilently(File file) {
        if (file != null && file.exists()) {
            boolean deleted = file.delete();
            if (!deleted) log.warn("Could not delete temp file: {}", file.getAbsolutePath());
        }
    }
}