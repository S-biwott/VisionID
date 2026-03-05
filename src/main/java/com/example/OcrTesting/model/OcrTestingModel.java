package com.example.OcrTesting.model;

import lombok.Data;

@Data
public class OcrTestingModel {

    private String fullName;
    private String idNumber;        // null for passports
    private String passportNumber;  // null for non-passport documents
    private String dateOfBirth;
    private String documentType;
    private String faceImageBase64;
    private String rawText;
}