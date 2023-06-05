package com.example.aiapplication;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.example.aiapplication.camera.Photo;
import com.example.aiapplication.image.ImageInfo;
import com.example.aiapplication.layout.MedicineResultActivity;
import com.example.aiapplication.layout.MyDataActivity;
import com.example.aiapplication.layout.UserActivity;
import com.example.aiapplication.medicine.service.MedicineService;
import com.example.aiapplication.server.PillCodeController;
import com.example.aiapplication.server.PillCodeRequester;
import com.example.aiapplication.user.dao.ActiveUserProfile;
import com.example.aiapplication.user.service.UserService;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.gson.Gson;
import com.gun0912.tedpermission.PermissionListener;
import com.gun0912.tedpermission.TedPermission;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.List;

import javax.annotation.Nullable;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_IMAGE_CAPTURE = 672;
    private static final int TIME_INTERVAL = 2000;
    private long backPressedTime;

    private ActivityResultLauncher<Intent> resultLauncher;

    private Photo photo;
    private ImageInfo imageInfo = ImageInfo.getInstance();

    private ActiveUserProfile activeUserProfile;
    private PillCodeController pillCodeController;

    @Override
    public void onBackPressed() {
        long currentTime = System.currentTimeMillis();

        if (backPressedTime + TIME_INTERVAL > currentTime) {
            super.onBackPressed();
            finish();
        }else{
            showToastMessage("뒤로 버튼을 두 번 눌러주세요");
        }

        backPressedTime = currentTime;

    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        MedicineService.getInstance(getApplicationContext());
        UserService.getInstance(getApplicationContext());

        activeUserProfile = ActiveUserProfile.getInstance(this);
        photo = new Photo(); // 카메라 인스턴스 생성
        pillCodeController = PillCodeRequester.getApiService();

        //권한 체크
        TedPermission.with(getApplicationContext())
                .setPermissionListener(permissionListener)
                .setRationaleMessage("카메라 권한이 필요합니다.")
                .setDeniedMessage("거부하셨습니다.")
                .setPermissions(android.Manifest.permission.CAMERA, android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                .check();

        if (imageInfo.getBitmap().isPresent()) {
            setImageView(imageInfo.getBitmap().get());
        }


        resultLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                new ActivityResultCallback<ActivityResult>() {
                    @Override
                    public void onActivityResult(ActivityResult result) {
                        if (result.getResultCode() == Activity.RESULT_OK) {
                            Bitmap bitmap = photo.afterTakePicture();
                            addCameraTextMessage();
                            setImageView(bitmap);
                            imageInfo.setBitmap(bitmap);
                        }
                    }
                }
        );

    }

    private void setImageView(Bitmap bitmap) {
        ImageView imageView = findViewById(R.id.iv_result);
        imageView.setImageBitmap(bitmap);
    }

    PermissionListener permissionListener = new PermissionListener() {
        @Override
        public void onPermissionGranted() {
            showToastMessage("권한이 허용되었습니다.");
        }

        @Override
        public void onPermissionDenied(List<String> deniedPermissions) {
            showToastMessage("권한이 거부되었습니다.");
        }
    };



    public void clickCameraButton(View view) {
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (intent.resolveActivity(getPackageManager()) != null) {
            File photoFile = null;
            try {
                photoFile = photo.createImageFile(getApplicationContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES));
            } catch (IOException e) {

            }

            if (photoFile != null) {
                Uri photoUri = FileProvider.getUriForFile(getApplicationContext(), getPackageName(), photoFile);
                photo.setPhotoUri(photoUri);
                intent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri);
                resultLauncher.launch(intent);
            }
        }
    }

    private void addCameraTextMessage() {
        TextView textView = findViewById(R.id.camera_info);
        textView.setText("사진을 다시 찍고 싶으시면 이미지를 다시 터치해주세요");
    }

    public void clickMyDataButton(View view) {
        Intent intent = new Intent(getApplicationContext(), MyDataActivity.class);
        startActivity(intent);
    }

    public void clickSettingButton(View view) {
        Intent intent = new Intent(getApplicationContext(), UserActivity.class);
        startActivity(intent);
    }

    public void clickImageAnalyzeButton(View view) {
        if (imageInfo.getBitmap().isPresent()) {
            Log.i("MainActivity", "결과 페이지로 INTENT 시도");
            /*
             * TODO :: 2023-05-21
             *  1. 여기서 찍은 이미지를 AI에 넘겨줘야 한다.
             *  2. 지금은 촬영 후 결과 페이지에 bitmap 데이터를 넘겨주기만 한다. => MedicineResultActivity
             *   - 후보2 : HTTP 통신 프로토콜을 활용해서 서버에 띄어져있는 AI 모델에게 사진을 전달한 후 DB에서 정보를 가져온다.
             * */
            File imageFile = imageInfo.convertBitmapToFile(getApplicationContext());
            RequestBody requestBody = RequestBody.create(imageFile, MediaType.parse("image/*"));
            MultipartBody.Part imagePart = MultipartBody.Part.createFormData("image", imageFile.getName(), requestBody);
            Call<ResponseBody> call = pillCodeController.detectImage(imagePart);
            call.enqueue(new Callback<ResponseBody>() {
                @Override
                public void onResponse(Call<ResponseBody> call, Response<ResponseBody> response) {
                    if (response.isSuccessful()) {
                        ResponseBody responseBody = response.body();
                        if (responseBody != null) {
                            try {
                                Log.i("MainActivity", "body : " + responseBody.string());
                                /*
                                * TODO :: responseBody는 body : {"result":"Detect Nothing"} 이렇게 들어온다.
                                *  - JSON객체 또는 문자열 파싱으로 result에 대한 value를 가져온다.
                                *  - Firebase에서 해당되는 key에 대한 값을 찾아온다.
                                * */
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                            Intent intent = new Intent(getApplicationContext(), MedicineResultActivity.class);
                            startActivity(intent);
//                            try {
////                                String responseString = String.valueOf(responseBody.byteString());
////                                JSONObject jsonObject = new JSONObject(responseString);
////
////                                String result = jsonObject.getString("result");
////                                String imageBase64 = jsonObject.getString("image");
////
////                                Log.i("MainActivity", "이미지 검출" + result);
////
////                                byte[] decodedBytes = Base64.decode(imageBase64, Base64.DEFAULT);
////                                Bitmap bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);
//
//                                // TODO :: 전달된 Response를 intent할 때 넘겨주자 여기서 Response는 알약코드 or "Detect Nothing"문구이다.
//                                // TODO :: 그리고 시간이된다면 로딩화면도 구현하자. 명진이 알람도 코드에 결합시켜야함. 결과 UI도 꾸며야함. 병용여부 Dialog도 만들어야한다.
//                                Intent intent = new Intent(getApplicationContext(), MedicineResultActivity.class);
//                                startActivity(intent);
//                            } catch (JSONException e) {
//                                throw new RuntimeException(e);
//                            } catch (IOException e) {
//                                throw new RuntimeException(e);
//                            }
                        }
                    }else {
                        showToastMessage("서버에서 잘못된 응답이 왔습니다.");
                    }
                }

                @Override
                public void onFailure(Call<ResponseBody> call, Throwable t) {
                    showToastMessage("서버와의 통신이 실패했습니다.");
                    t.printStackTrace();
                }
            });

        }else{
            showToastMessage("사진을 찍어야 분석이 가능합니다.");
        }
    }

    private void showToastMessage(String message) {
        Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show();
    }
}
