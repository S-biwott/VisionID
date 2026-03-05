VisionID 

VisionID is an intelligent OCR-based identity document recognition system built with Spring Boot, OpenCV, and Tess4J.

It automatically detects Kenyan identity documents, extracts text using Optical Character Recognition (OCR), detects the ID holder's face, and returns structured data for use in visitor management systems, security checkpoints, and digital identity verification.
The system supports both image uploads and live webcam scanning.

FEATURES

Multi-document support — automatically detects and parses Kenyan ID documents.
Multi-strategy OCR pipeline — runs multiple preprocessing strategies and selects the best OCR result.
Automatic document detection — identifies and crops the ID card from the image.
Structured data extraction — returns parsed identity fields as JSON.
Face detection and extraction — crops the ID photo using OpenCV Haar cascade.
Webcam scanning support — security guards can capture IDs in real time.
Fuzzy OCR correction — handles common OCR misreads on Kenyan IDs.
Spring Boot REST API — easy integration with Angular, React, or mobile apps.

Supported Document Types:

Document	                 Fields Extracted
Old National - ID	Full Name, ID Number, Date of Birth
New Maisha - ID Card	Surname, Given Name, ID Number, Date of Birth
Passport	- Full Name, Passport Number, Date of Birth
Driver's Licence	- Full Name, National ID Number, Date of Birth

Example Output
{
  "fullName": "JOHN DOE",
  "idNumber": "12345678",
  "passportNumber": null,
  "dateOfBirth": "01/01/2000",
  "documentType": "NATIONAL_ID",
  "faceImageBase64": "iVBORw0KGgoAAAANSUhEUgAA...",
  "rawText": "SASANURI YA KENYA..."
}

TECH STACK
Backend - Framework	Spring Boot 3
Programming Language -	Java
OCR Engine - Tesseract 5 via Tess4J
Computer Vision -	OpenCV via Bytedeco JavaCV
Face Detection -	Haar Cascade Classifier
Build Tool	- Maven
Server	- Embedded Tomcat

System Architecture

VisionID processes identity documents through the following pipeline:
1️⃣ Image upload or webcam capture
2️⃣ Image preprocessing using OpenCV
3️⃣ Automatic document detection and cropping
4️⃣ Multi-strategy OCR execution
5️⃣ Face detection on the ID photo
6️⃣ Field parsing using regex and fuzzy correction
7️⃣ Structured JSON response returned via REST API

OCR Processing Algorithm

The system follows this algorithm:
Step 1 – Image Acquisition
The system receives an image from either:
File upload endpoint
Webcam capture endpoint

Step 2 – Image Preprocessing
The image is improved using OpenCV techniques to enhance OCR accuracy.
Preprocessing steps include:
Image resizing
Grayscale conversion
Noise reduction
Contrast enhancement
Thresholding

Step 3 – Document Detection
The system identifies the ID card by detecting the largest rectangular object.
Algorithm:
Convert image to grayscale
Apply Gaussian blur
Run Canny edge detection
Detect contours
Identify the largest rectangle
Crop the detected document

Step 4 – Multi-Strategy OCR
Four preprocessing pipelines are executed:
Strategy	Technique	Best For
1	Gaussian blur + Otsu threshold	Clean scans
2	Histogram equalisation + adaptive threshold	Patterned backgrounds (Maisha card)
3	Bilateral filtering + adaptive threshold	Noisy camera photos
4	Laplacian sharpening + Otsu threshold	Blurry images
The result with the highest alphanumeric character count is selected.

Step 5 – Text Extraction
The selected image is passed to Tesseract OCR using Tess4J.
Tesseract extracts raw text from the document.

Step 6 – Face Detection
OpenCV loads the Haar Cascade classifier and detects the ID photo.
The face region is:
Cropped
Converted to PNG
Encoded as Base64

Step 7 – Information Parsing
Regex rules extract identity information:
Full Name
ID Number
Passport Number
Date of Birth
Document Type
Fuzzy OCR correction improves recognition of common misreads.

Example corrections:
OCR Error	Correct Word
SURMUADAE	SURNAME
NUMIE	NUMBER
NOME	NO
KITAMBULIGNO	KITAMBULISHO

Project Structure
VisionID/
├── src/
│   └── main/
│       ├── java/com/example/OcrTesting/
│       │   ├── controller/
│       │   │   └── OcrTestingController.java
│       │   │
│       │   ├── service/
│       │   │   └── OcrTestingService.java
│       │   │
│       │   ├── model/
│       │   │   └── OcrTestingModel.java
│       │   │
│       │   └── OcrTestingApplication.java
│       │
│       └── resources/
│           ├── haarcascade_frontalface_alt.xml
│           └── application.properties
│
├── pom.xml
└── README.md
Configuration

Edit:
src/main/resources/application.properties
tesseract.datapath=C:/Program Files/Tesseract-OCR/tessdata
logging.level.org.bytedeco=WARN
server.port=8080
Running the Application

Clone the repository

1. git clone https://github.com/YOUR_USERNAME/VisionID.git
2. cd VisionID
3. Build the project
4. mvn clean install
5. Run the application
6. mvn spring-boot:run

The API will be available at:
http://localhost:8080
API Endpoints
Health Check
GET /api/ocr/test

Response:

OCR API is running
Upload ID Image
POST /api/ocr/upload-id

Request type:

multipart/form-data

Example:

curl -X POST http://localhost:8080/api/ocr/upload-id \
-F "file=@/path/to/id.jpg"
Scan ID Using Webcam
GET /api/ocr/scan-camera

Captures a frame from the system webcam and processes it.

Response Fields
Field	Description
fullName	Extracted full name
idNumber	National ID number
passportNumber	Passport number if detected
dateOfBirth	Date of birth
documentType	Detected document type
faceImageBase64	Base64 encoded ID photo
rawText	Raw OCR output
Image Tips for Best OCR Accuracy

For the best results:
Keep the document flat
Avoid shadows
Ensure full document visibility
Maintain camera alignment
Use good lighting
Prefer PNG for scanned documents
Known Limitations
OCR accuracy depends on image quality
Pattern-heavy backgrounds may affect number extraction
Haar cascade face detection may fail for angled photos
Only the best OCR result is retained
Frontend Integration
CORS is enabled for Angular development.
http://localhost:4200

To change this, update:

@CrossOrigin(origins = "http://localhost:4200")

Use Cases

VisionID can be integrated into:
Visitor Management Systems
Airport check-in verification
Building security access control
Digital identity verification systems
Hotel guest registration

Future Improvements
Deep learning based ID detection
YOLO document detection
Mobile camera integration
Database storage for visitor logs
ID fraud detection
Real-time OCR preview

Author

Elvin Biwott
Software Developer
