package com.example.veinscope;

import android.Manifest;
import android.app.ProgressDialog;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import com.google.common.util.concurrent.ListenableFuture;
import com.karumi.dexter.Dexter;
import com.karumi.dexter.MultiplePermissionsReport;
import com.karumi.dexter.PermissionToken;
import com.karumi.dexter.listener.PermissionRequest;
import com.karumi.dexter.listener.multi.MultiplePermissionsListener;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutionException;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {

    // Camera components
    private PreviewView cameraPreview;
    private ImageCapture imageCapture;
    private CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

    // Network components
    private final OkHttpClient httpClient = new OkHttpClient();
    private ProgressDialog progressDialog;

    // Server configuration (UPDATE THESE VALUES)

    private static final String SERVER_IP = "172.19.4.132"; // Your computer's IP
    private static final int SERVER_PORT = 8000;              // Python server port
    private static final String UPLOAD_ENDPOINT = "/upload";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initializeViews();
        setupPermissions();
    }

    private void initializeViews() {
        cameraPreview = findViewById(R.id.camera_preview);
        Button captureBtn = findViewById(R.id.btn_capture);
        Button flipBtn = findViewById(R.id.btn_flip);

        progressDialog = new ProgressDialog(this);
        progressDialog.setMessage("Uploading...");
        progressDialog.setCancelable(false);

        captureBtn.setOnClickListener(v -> takePhoto());
        flipBtn.setOnClickListener(v -> flipCamera());
    }

    private void setupPermissions() {
        Dexter.withContext(this)
                .withPermissions(
                        Manifest.permission.CAMERA,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        Manifest.permission.INTERNET
                )
                .withListener(new MultiplePermissionsListener() {
                    @Override
                    public void onPermissionsChecked(MultiplePermissionsReport report) {
                        if (report.areAllPermissionsGranted()) {
                            startCamera();
                        } else {
                            showToast("All permissions are required");
                        }
                    }

                    @Override
                    public void onPermissionRationaleShouldBeShown(
                            List<PermissionRequest> permissions,
                            PermissionToken token
                    ) {
                        token.continuePermissionRequest();
                    }
                }).check();
    }

    private void takePhoto() {
        File imageFile = new File(getExternalFilesDir(null),
                "vein_scan_" + System.currentTimeMillis() + ".jpg");

        ImageCapture.OutputFileOptions outputOptions =
                new ImageCapture.OutputFileOptions.Builder(imageFile).build();

        imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults results) {
                        runOnUiThread(() -> {
                            showToast("Image captured");
                            uploadToServer(imageFile);
                        });
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException ex) {
                        runOnUiThread(() ->
                                showToast("Capture failed: " + ex.getMessage())
                        );
                    }
                });
    }

    private void uploadToServer(File imageFile) {
        if (!imageFile.exists()) {
            showToast("Image file not found");
            return;
        }

        progressDialog.show();

        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                        "image",
                        imageFile.getName(),
                        RequestBody.create(imageFile, MediaType.get("image/jpeg"))
                )
                .build();

        Request request = new Request.Builder()
                .url("http://" + SERVER_IP + ":" + SERVER_PORT + UPLOAD_ENDPOINT)
                .post(requestBody)
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    showToast("Upload failed: " + e.getMessage());
                    Log.e("NETWORK", "Upload error", e);
                });
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    try {
                        if (response.isSuccessful()) {
                            showToast("Upload successful!");
                            Log.d("UPLOAD", "Server response: " + response.body().string());
                        } else {
                            String error = response.body() != null ?
                                    response.body().string() : "Unknown error";
                            showToast("Server error: " + response.code());
                            Log.e("SERVER", "Error code: " + response.code() + " | " + error);
                        }
                    } catch (IOException e) {
                        Log.e("RESPONSE", "Error handling response", e);
                    } finally {
                        response.close();
                    }
                });
            }
        });
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(cameraPreview.getSurfaceProvider());

                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build();

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(
                        this, cameraSelector, preview, imageCapture
                );
            } catch (ExecutionException | InterruptedException e) {
                Log.e("CAMERA", "Camera initialization failed", e);
                showToast("Camera startup failed");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void flipCamera() {
        cameraSelector = (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) ?
                CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;
        startCamera();
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }


}


