package com.example.OcrTesting.service;

import com.example.OcrTesting.model.OcrTestingModel;
import jakarta.annotation.PostConstruct;
import net.sourceforge.tess4j.Tesseract;
import org.bytedeco.opencv.opencv_core.*;
import org.bytedeco.opencv.opencv_objdetect.CascadeClassifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.util.Base64;

import static org.bytedeco.opencv.global.opencv_imgcodecs.*;
import static org.bytedeco.opencv.global.opencv_imgproc.*;

@Service
public class OcrTestingService {

    @Value("${tesseract.datapath}")
    private String tessDataPath;

    private CascadeClassifier faceDetector;

    @PostConstruct
    public void init() {
        try (InputStream stream = getClass().getClassLoader()
                .getResourceAsStream("haarcascade_frontalface_alt.xml")) {
            if (stream == null) {
                throw new IllegalStateException(
                        "haarcascade_frontalface_alt.xml not found in src/main/resources/. " +
                                "Download it from the OpenCV GitHub repository and place it there.");
            }
            File tmp = File.createTempFile("haarcascade_", ".xml");
            tmp.deleteOnExit();

            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                stream.transferTo(fos);
            }
            faceDetector = new CascadeClassifier(tmp.getAbsolutePath());

            if (faceDetector.empty()) {
                throw new IllegalStateException(
                        "CascadeClassifier loaded but is empty — the XML file may be corrupt.");
            }

            System.out.println("✔  Haar cascade loaded successfully.");

        } catch (IOException e) {
            throw new RuntimeException("Failed to initialise face detector", e);
        }
    }

    public OcrTestingModel extractText(File imageFile) {
        long start = System.currentTimeMillis();

        try {
            File processedImage = preprocessImage(imageFile);

            String ocrResult = runOcr(processedImage);
            System.out.println("OCR RESULT:\n" + ocrResult);

            String faceBase64 = detectFaceBase64(processedImage);

            System.out.printf("TOTAL PROCESS TIME: %dms%n", System.currentTimeMillis() - start);

            OcrTestingModel model = new OcrTestingModel();
            model.setRawText(ocrResult);
            model.setFaceImageBase64(faceBase64);
            return model;

        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("OCR processing failed: " + e.getMessage(), e);
        }
    }

    private String runOcr(File imageFile) throws Exception {
        Tesseract tesseract = new Tesseract();
        tesseract.setDatapath(tessDataPath);
        tesseract.setLanguage("eng");
        tesseract.setOcrEngineMode(1);
        tesseract.setPageSegMode(6);

        // Improve OCR quality
        tesseract.setTessVariable("user_defined_dpi", "300");
        tesseract.setTessVariable("textord_heavy_nr", "1");
        tesseract.setTessVariable("preserve_interword_spaces", "1");

        // NOTE: Do NOT set tess edit_char_whitelist — it degrades LSTM accuracy for documents that contain mixed alphanumeric content like passports.
        return tesseract.doOCR(imageFile);
    }

    private File preprocessImage(File originalImage) {
        Mat image = imread(originalImage.getAbsolutePath());
        if (image.empty()) throw new RuntimeException("Image failed to load: " + originalImage.getAbsolutePath());

        // Resize
        Mat resized = new Mat();
        resize(image, resized, new Size(1600, 1000));

        // Crop to the largest document-shaped region if detectable
        Mat doc = detectDocument(resized);

        // Greyscale
        Mat gray = new Mat();
        cvtColor(doc, gray, COLOR_BGR2GRAY);

        // Gentle Gaussian blur to reduce scanner/camera noise
        Mat blurred = new Mat();
        GaussianBlur(gray, blurred, new Size(3, 3), 0);

        // Otsu binarisation — automatically picks the best global threshold
        Mat binary = new Mat();
        threshold(blurred, binary, 0, 255, THRESH_BINARY | THRESH_OTSU);

        try {
            File out = File.createTempFile("processed-", ".png");
            out.deleteOnExit();
            imwrite(out.getAbsolutePath(), binary);
            return out;
        } catch (IOException e) {
            throw new RuntimeException("Could not write preprocessed image", e);
        }
    }

    private Mat detectDocument(Mat image) {
        Mat gray = new Mat();
        cvtColor(image, gray, COLOR_BGR2GRAY);

        Mat blurred = new Mat();
        GaussianBlur(gray, blurred, new Size(5, 5), 0);

        Mat edges = new Mat();
        Canny(blurred, edges, 75, 200);

        MatVector contours = new MatVector();
        Mat hierarchy = new Mat();
        findContours(edges, contours, hierarchy, RETR_LIST, CHAIN_APPROX_SIMPLE);

        double imageArea = (double) image.rows() * image.cols();
        double maxArea   = 0;
        Rect   bestRect  = null;

        for (int i = 0; i < contours.size(); i++) {
            Rect   rect = boundingRect(contours.get(i));
            double area = rect.area();

            double ratio = area / imageArea;
            if (ratio > 0.10 && ratio < 0.95 && area > maxArea) {
                maxArea  = area;
                bestRect = rect;
            }
        }

        if (bestRect == null) return image;
        // Safety clamp so the rect stays inside the image bounds
        int x = Math.max(0, bestRect.x());
        int y = Math.max(0, bestRect.y());
        int w = Math.min(bestRect.width(),  image.cols() - x);
        int h = Math.min(bestRect.height(), image.rows() - y);
        return new Mat(image, new Rect(x, y, w, h));
    }

    private String detectFaceBase64(File imageFile) {
        try {
            Mat image = imread(imageFile.getAbsolutePath());
            if (image.empty()) return null;

            RectVector faces = new RectVector();
            faceDetector.detectMultiScale(image, faces, 1.1,3,0, new Size(30, 30), new Size() );

            if (faces.size() == 0) return null;

            // Use the first (largest, highest-confidence) detection
            Rect faceRect = faces.get(0);

            // Clamp to image bounds
            int x = Math.max(0, faceRect.x());
            int y = Math.max(0, faceRect.y());
            int w = Math.min(faceRect.width(),  image.cols() - x);
            int h = Math.min(faceRect.height(), image.rows() - y);
            Mat face = new Mat(image, new Rect(x, y, w, h));

            File faceFile = File.createTempFile("face-", ".png");
            faceFile.deleteOnExit();
            imwrite(faceFile.getAbsolutePath(), face);

            byte[] bytes = java.nio.file.Files.readAllBytes(faceFile.toPath());
            faceFile.delete();
            return Base64.getEncoder().encodeToString(bytes);

        } catch (Exception e) {
            System.err.println("Face detection failed: " + e.getMessage());
            return null;
        }
    }
}