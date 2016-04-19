package com.imagepicker;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.widget.ArrayAdapter;
import android.webkit.MimeTypeMap;
import android.content.pm.PackageManager;
import android.provider.OpenableColumns;

import com.facebook.react.bridge.ActivityEventListener;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReadableMapKeySetIterator;
import com.facebook.react.bridge.WritableMap;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ImagePickerModule extends ReactContextBaseJavaModule implements ActivityEventListener {

  static final int REQUEST_LAUNCH_IMAGE_CAPTURE = 1;
  static final int REQUEST_LAUNCH_IMAGE_LIBRARY = 2;
  static final int REQUEST_LAUNCH_VIDEO_LIBRARY = 3;
  static final int REQUEST_LAUNCH_VIDEO_CAPTURE = 4;
  static final int READ_REQUEST_SAF = 42;



  private final ReactApplicationContext mReactContext;

  private Uri mCameraCaptureURI;
  private Uri mCropImagedUri;
  private Callback mCallback;
  private Boolean noData = false;
  private Boolean tmpImage;
  private Boolean allowEditing = false;
  private Boolean pickVideo = false;
  private int maxWidth = 0;
  private int maxHeight = 0;
  private int aspectX = 0;
  private int aspectY = 0;
  private int quality = 100;
  private int angle = 0;
  private Boolean forceAngle = false;
  private int videoQuality = 1;
  private int videoDurationLimit = 0;
  private String mimeFilter = null;
  WritableMap response;

  public ImagePickerModule(ReactApplicationContext reactContext) {
    super(reactContext);

    reactContext.addActivityEventListener(this);

    mReactContext = reactContext;
  }

  @Override
  public String getName() {
    return "ImagePickerManager";
  }

  @ReactMethod
  public void showImagePicker(final ReadableMap options, final Callback callback) {
    Activity currentActivity = getCurrentActivity();

    if (currentActivity == null) {
      response = Arguments.createMap();
      response.putString("error", "can't find current Activity");
      callback.invoke(response);
      return;
    }

    final List<String> titles = new ArrayList<String>();
    final List<String> actions = new ArrayList<String>();

    String cancelButtonTitle = getReactApplicationContext().getString(android.R.string.cancel);

    if (options.hasKey("takePhotoButtonTitle")
            && options.getString("takePhotoButtonTitle") != null
            && !options.getString("takePhotoButtonTitle").isEmpty()
            && isCameraAvailable()) {
      titles.add(options.getString("takePhotoButtonTitle"));
      actions.add("photo");
    }
    if (options.hasKey("chooseFromLibraryButtonTitle")
            && options.getString("chooseFromLibraryButtonTitle") != null
            && !options.getString("chooseFromLibraryButtonTitle").isEmpty()) {
      titles.add(options.getString("chooseFromLibraryButtonTitle"));
      actions.add("library");
    }
    if (options.hasKey("cancelButtonTitle")
            && !options.getString("cancelButtonTitle").isEmpty()) {
      cancelButtonTitle = options.getString("cancelButtonTitle");
    }

    if (options.hasKey("customButtons")) {
      ReadableMap buttons = options.getMap("customButtons");
      ReadableMapKeySetIterator it = buttons.keySetIterator();
      // Keep the current size as the iterator returns the keys in the reverse order they are defined
      int currentIndex = titles.size();
      while (it.hasNextKey()) {
        String key = it.nextKey();

        titles.add(currentIndex, key);
        actions.add(currentIndex, buttons.getString(key));
      }
    }

    titles.add(cancelButtonTitle);
    actions.add("cancel");

    ArrayAdapter<String> adapter = new ArrayAdapter<String>(currentActivity,
            android.R.layout.select_dialog_item, titles);
    AlertDialog.Builder builder = new AlertDialog.Builder(currentActivity);
    if (options.hasKey("title") && options.getString("title") != null && !options.getString("title").isEmpty()) {
      builder.setTitle(options.getString("title"));
    }

    builder.setAdapter(adapter, new DialogInterface.OnClickListener() {
      public void onClick(DialogInterface dialog, int index) {
        String action = actions.get(index);
        response = Arguments.createMap();

        switch (action) {
          case "photo":
            launchCamera(options, callback);
            break;
          case "library":
            launchImageLibrary(options, callback);
            break;
          case "cancel":
            response.putBoolean("didCancel", true);
            callback.invoke(response);
            break;
          default: // custom button
            response.putString("customButton", action);
            callback.invoke(response);
        }
      }
    });

    final AlertDialog dialog = builder.create();
    /**
     * override onCancel method to callback cancel in case of a touch outside of
     * the dialog or the BACK key pressed
     */
    dialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
      @Override
      public void onCancel(DialogInterface dialog) {
        response = Arguments.createMap();
        dialog.dismiss();
        response.putBoolean("didCancel", true);
        callback.invoke(response);
      }
    });
    dialog.show();
  }

  // NOTE: Currently not reentrant / doesn't support concurrent requests
  @ReactMethod
  public void launchCamera(final ReadableMap options, final Callback callback) {
    int requestCode;
    Intent cameraIntent;
    response = Arguments.createMap();

    if (!isCameraAvailable()) {
        response.putString("error", "Camera not available");
        callback.invoke(response);
        return;
    }

    Activity currentActivity = getCurrentActivity();
    if (currentActivity == null) {
      response.putString("error", "can't find current Activity");
      callback.invoke(response);
      return;
    }

    parseOptions(options);

    if (pickVideo == true) {
      requestCode = REQUEST_LAUNCH_VIDEO_CAPTURE;
      cameraIntent = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);
      cameraIntent.putExtra(MediaStore.EXTRA_VIDEO_QUALITY, videoQuality);
      if (videoDurationLimit > 0) {
        cameraIntent.putExtra(MediaStore.EXTRA_DURATION_LIMIT, videoDurationLimit);
      }
    } else {
      requestCode = REQUEST_LAUNCH_IMAGE_CAPTURE;
      cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);

      // we create a tmp file to save the result
      File imageFile = createNewFile(true);
      mCameraCaptureURI = Uri.fromFile(imageFile);
      cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, Uri.fromFile(imageFile));

      if (allowEditing == true) {
        cameraIntent.putExtra("crop", "true");
        cameraIntent.putExtra("aspectX", aspectX);
        cameraIntent.putExtra("aspectY", aspectY);
      }
    }

    if (cameraIntent.resolveActivity(mReactContext.getPackageManager()) == null) {
      response.putString("error", "Cannot launch camera");
      callback.invoke(response);
      return;
    }

    mCallback = callback;

    try {
      currentActivity.startActivityForResult(cameraIntent, requestCode);
    } catch (ActivityNotFoundException e) {
      e.printStackTrace();
      response = Arguments.createMap();
      response.putString("error", "Cannot launch camera");
      callback.invoke(response);
    }
  }

  // NOTE: Currently not reentrant / doesn't support concurrent requests
  @ReactMethod
  public void launchImageLibrary(final ReadableMap options, final Callback callback) {
    int requestCode;
    Intent libraryIntent;
    Activity currentActivity = getCurrentActivity();

    if (currentActivity == null) {
      response = Arguments.createMap();
      response.putString("error", "can't find current Activity");
      callback.invoke(response);
      return;
    }

    parseOptions(options);

    if (pickVideo == true) {
      requestCode = REQUEST_LAUNCH_VIDEO_LIBRARY;
      libraryIntent = new Intent(Intent.ACTION_PICK);
      libraryIntent.setType("video/*");
    } else {
      requestCode = READ_REQUEST_SAF;
      libraryIntent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
      libraryIntent.addCategory(Intent.CATEGORY_OPENABLE);
      libraryIntent.setType(mimeFilter);
    }

    if (libraryIntent.resolveActivity(mReactContext.getPackageManager()) == null) {
      response = Arguments.createMap();
      response.putString("error", "Cannot launch photo library");
      callback.invoke(response);
      return;
    }

    mCallback = callback;

    try {
      currentActivity.startActivityForResult(libraryIntent, requestCode);
    } catch (ActivityNotFoundException e) {
      e.printStackTrace();
      response = Arguments.createMap();
      response.putString("error", "Cannot launch photo library");
      callback.invoke(response);
    }
  }

  @Override
  public void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
    //robustness code
    if (mCallback == null || (mCameraCaptureURI == null && requestCode == REQUEST_LAUNCH_IMAGE_CAPTURE)
            || (requestCode != REQUEST_LAUNCH_IMAGE_CAPTURE && requestCode != REQUEST_LAUNCH_IMAGE_LIBRARY
            && requestCode != REQUEST_LAUNCH_VIDEO_LIBRARY && requestCode != REQUEST_LAUNCH_VIDEO_CAPTURE  && requestCode != READ_REQUEST_SAF)) {
      return;
    }

    response = Arguments.createMap();

    // user cancel
    if (resultCode != Activity.RESULT_OK) {
      response.putBoolean("didCancel", true);
      mCallback.invoke(response);
      return;
    }

    Uri uri;
    Uri orginalUri = null;

    switch (requestCode) {
      case REQUEST_LAUNCH_IMAGE_CAPTURE:
        uri = mCameraCaptureURI;
        break;
      case READ_REQUEST_SAF:
        orginalUri = uri = data.getData();
        putExtraFileInfoSAF(orginalUri, response);
        response.putString("uriOrginal", uri.toString());
        break;
      case REQUEST_LAUNCH_VIDEO_LIBRARY:
        response.putString("uri", data.getData().toString());
        mCallback.invoke(response);
        return;
      case REQUEST_LAUNCH_VIDEO_CAPTURE:
        response.putString("uri", data.getData().toString());
        mCallback.invoke(response);
        return;
      default:
        uri = null;
    }

    String realPath = null;
    URL url = null;
    boolean isUrl = false;

    if(requestCode != READ_REQUEST_SAF){
      realPath = getRealPathFromURI(uri);
    }else if(response.hasKey("path")){
      realPath = response.getString("path");
      uri = Uri.fromFile(new File(realPath));
    }

    if (realPath != null) {
      try {
        url = new URL(realPath);
        isUrl = true;
      } catch (MalformedURLException e) {
        // not a url
      }
    }

    if(!response.hasKey("type")){
      String extension = MimeTypeMap.getFileExtensionFromUrl(uri.getLastPathSegment());
      if (extension != null) {
        response.putString("type", MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension));
      }
    }

	// image isn't in memory cache and if we're using SAF we can't pass those content uris around.. so we need to download the file
	if (realPath == null || isUrl) {
	  try {
          File file;
          if(response.hasKey("fileName")) {
            file = createFileFromURI(uri, Uri.parse(response.getString("fileName")).getLastPathSegment());
          }else{
            file = createFileFromURI(uri);
          }
		  realPath = file.getAbsolutePath();
		  uri = Uri.fromFile(file);
	  } catch (Exception e) {
		  // image not in cache
		  response.putString("error", "Could not read file");
		  response.putString("uri", uri.toString());
		  mCallback.invoke(response);
		  return;
	  }
	}

    if(response.hasKey("type") && (response.getString("type").startsWith("image/") || response.getString("type").startsWith("video/"))){



      int CurrentAngle = 0;
      try {
        ExifInterface exif = new ExifInterface(realPath);
        int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        boolean isVertical = true;
        switch (orientation) {
          case ExifInterface.ORIENTATION_ROTATE_270:
            isVertical = false;
            CurrentAngle = 270;
            break;
          case ExifInterface.ORIENTATION_ROTATE_90:
            isVertical = false;
            CurrentAngle = 90;
            break;
          case ExifInterface.ORIENTATION_ROTATE_180:
            CurrentAngle = 180;
            break;
        }
        response.putBoolean("isVertical", isVertical);
      } catch (IOException e) {
        e.printStackTrace();
        response.putString("error", e.getMessage());
        mCallback.invoke(response);
        return;
      }

      BitmapFactory.Options options = new BitmapFactory.Options();
      options.inJustDecodeBounds = true;
      Bitmap photo = BitmapFactory.decodeFile(realPath, options);
      int initialWidth = options.outWidth;
      int initialHeight = options.outHeight;

      // don't create a new file if contraint are respected
      if (((initialWidth < maxWidth && maxWidth > 0) || maxWidth == 0)
              && ((initialHeight < maxHeight && maxHeight > 0) || maxHeight == 0)
              && quality == 100 && (!forceAngle || (forceAngle && CurrentAngle == angle))) {
        response.putInt("width", initialWidth);
        response.putInt("height", initialHeight);
      } else {
        File resized = getResizedImage(realPath, initialWidth, initialHeight);
        if (resized == null) {
          response.putString("error", "Can't resize the image");
        } else {
           realPath = resized.getAbsolutePath();
           uri = Uri.fromFile(resized);
           photo = BitmapFactory.decodeFile(realPath, options);
           response.putInt("width", options.outWidth);
           response.putInt("height", options.outHeight);
        }
      }

      if (!noData) {
        response.putString("data", getBase64StringFromFile(realPath));
      }
    }else if(!noData){
      try {
        response.putString("data", getBase64StringFromFile(createFileFromURI(uri).getAbsolutePath()));
      }catch(Exception e){
        e.printStackTrace();
      }
    }

    response.putString("uri", uri.toString());
    response.putString("path", realPath);



    if(requestCode != READ_REQUEST_SAF) {
      putExtraFileInfo(realPath, response);
    }

    mCallback.invoke(response);
  }

  @ReactMethod
  public void readUri(final String uriString, final Callback callback) {
    Uri uri = Uri.parse(uriString);

    try{
      callback.invoke(
              getBase64StringFromFile(
                      createFileFromURI(uri).getAbsolutePath()
              )
      );
    }catch(Exception e){
      response = Arguments.createMap();
      response.putString("error", e.getMessage());
      callback.invoke(response);
    }
  }

  @ReactMethod
  public void openFileURI(final String uri, final String mime){
    Intent intent = new Intent(Intent.ACTION_VIEW);
    intent.setDataAndType(Uri.parse(uri), mime);
    getCurrentActivity().startActivity(intent);
  }

  private boolean isCameraAvailable() {
    return mReactContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
  }

  private String getRealPathFromURI(Uri uri) {
    String result;
    String[] projection = {MediaStore.Images.Media.DATA};
    Cursor cursor = mReactContext.getContentResolver().query(uri, projection, null, null, null);
    if (cursor == null) { // Source is Dropbox or other similar local file path
      result = uri.getPath();
    } else {
      cursor.moveToFirst();
      int idx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
      result = cursor.getString(idx);
      cursor.close();
    }

    return result;
  }


  private File createFileFromURI(Uri uri) throws Exception {
    return createFileFromURI(uri, uri.getLastPathSegment());
  }

  /**
   * Create a file from uri to allow image picking of image in disk cache
   * (Exemple: facebook image, google image etc..)
   *
   * @doc =>
   * https://github.com/nostra13/Android-Universal-Image-Loader#load--display-task-flow
   *
   * @param uri
   * @return File
   * @throws Exception
   */
  private File createFileFromURI(Uri uri, String name) throws Exception {
    File file = new File(mReactContext.getCacheDir(), "file-" + name);
    file.setReadable(true, false); // World readable so we can pass the file to VIEW_ACTION
    InputStream input = mReactContext.getContentResolver().openInputStream(uri);
    OutputStream output = new FileOutputStream(file);

    try {
      byte[] buffer = new byte[4 * 1024];
      int read;
      while ((read = input.read(buffer)) != -1) {
        output.write(buffer, 0, read);
      }
      output.flush();
    } finally {
      output.close();
      input.close();
    }

    return file;
  }

  private String getBase64StringFromFile(String absolutepath) {
    InputStream inputStream = null;
    try {
      inputStream = new FileInputStream(absolutepath);
    } catch (FileNotFoundException e) {
      e.printStackTrace();
    }

    byte[] bytes;
    byte[] buffer = new byte[8192];
    int bytesRead;
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try {
      while ((bytesRead = inputStream.read(buffer)) != -1) {
        output.write(buffer, 0, bytesRead);
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
    bytes = output.toByteArray();
    return Base64.encodeToString(bytes, Base64.NO_WRAP);
  }

  /**
   * Create a resized image to fill the maxWidth/maxHeight values,the quality
   * value and the angle value
   *
   * @param realPath
   * @param initialWidth
   * @param initialHeight
   * @return resized file
   */
  private File getResizedImage(final String realPath, final int initialWidth, final int initialHeight) {
    Bitmap photo = BitmapFactory.decodeFile(realPath);

    if (photo == null) {
        return null;
    }

    Bitmap scaledphoto = null;
    if (maxWidth == 0) {
      maxWidth = initialWidth;
    }
    if (maxHeight == 0) {
      maxHeight = initialHeight;
    }
    double widthRatio = (double) maxWidth / initialWidth;
    double heightRatio = (double) maxHeight / initialHeight;

    double ratio = (widthRatio < heightRatio)
            ? widthRatio
            : heightRatio;

    Matrix matrix = new Matrix();
    matrix.postRotate(angle);
    matrix.postScale((float) ratio, (float) ratio);

    ExifInterface exif;
    try {
      exif = new ExifInterface(realPath);

      int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0);

      if (orientation == 6) {
        matrix.postRotate(90);
      } else if (orientation == 3) {
        matrix.postRotate(180);
      } else if (orientation == 8) {
        matrix.postRotate(270);
      }
    } catch (IOException e) {
      e.printStackTrace();
    }

    scaledphoto = Bitmap.createBitmap(photo, 0, 0, photo.getWidth(), photo.getHeight(), matrix, true);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    scaledphoto.compress(Bitmap.CompressFormat.JPEG, quality, bytes);

    File f = createNewFile(false);
    FileOutputStream fo;
    try {
      fo = new FileOutputStream(f);
      try {
        fo.write(bytes.toByteArray());
      } catch (IOException e) {
        e.printStackTrace();
      }
    } catch (FileNotFoundException e) {
      e.printStackTrace();
    }

    // recycle to avoid java.lang.OutOfMemoryError
    if (photo != null) {
      scaledphoto.recycle();
      photo.recycle();
      scaledphoto = null;
      photo = null;
    }
    return f;
  }

  /**
   * Create a new file
   *
   * @return an empty file
   */
  private File createNewFile(final boolean forcePictureDirectory) {
    String filename = "image-" + UUID.randomUUID().toString() + ".jpg";
    if (tmpImage && forcePictureDirectory != true) {
      return new File(mReactContext.getCacheDir(), filename);
    } else {
      File path = Environment.getExternalStoragePublicDirectory(
              Environment.DIRECTORY_PICTURES);
      File f = new File(path, filename);

      try {
        path.mkdirs();
        f.createNewFile();
      } catch (IOException e) {
        e.printStackTrace();
      }
      return f;
    }
  }

  private void putExtraFileInfo(final String path, WritableMap response) {
    // size && filename
    try {
      File f = new File(path);
      response.putDouble("fileSize", f.length());
      response.putString("fileName", f.getName());
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private void putExtraFileInfoSAF(Uri uri, WritableMap response) {
    Cursor cursor = mReactContext.getContentResolver().query(uri, null, null, null, null);

    try {
      if (cursor != null && cursor.moveToFirst()) {
        for(int i=0; i<cursor.getColumnCount();i++){
          switch(cursor.getColumnName(i)){
            case "mime_type":
              response.putString("type", cursor.getString(i));
              break;

            case "_size": // Google drive
            case "size":
              response.putString("fileSize", cursor.getString(i));
              break;

            case "_display_name": // Google drive
            case "display_name":
              response.putString("fileName", cursor.getString(i));
              break;

            case "path":
            case "filePath":
              response.putString("path", cursor.getString(i));
              break;
          }
          //response.putString("cursor_" + cursor.getColumnName(i), cursor.getString(i));
        }
      }
    } catch (Exception e) {
      response.putString("error", e.getMessage());
      e.printStackTrace();
    }
  }

  private void parseOptions(final ReadableMap options) {
    noData = false;
    if (options.hasKey("noData")) {
      noData = options.getBoolean("noData");
    }
    maxWidth = 0;
    if (options.hasKey("maxWidth")) {
      maxWidth = options.getInt("maxWidth");
    }
    maxHeight = 0;
    if (options.hasKey("maxHeight")) {
      maxHeight = options.getInt("maxHeight");
    }
    aspectX = 0;
    if (options.hasKey("aspectX")) {
      aspectX = options.getInt("aspectX");
    }
    aspectY = 0;
    if (options.hasKey("aspectY")) {
      aspectY = options.getInt("aspectY");
    }
    quality = 100;
    if (options.hasKey("quality")) {
      quality = (int) (options.getDouble("quality") * 100);
    }
    tmpImage = true;
    if (options.hasKey("storageOptions")) {
      tmpImage = false;
    }
    allowEditing = false;
    if (options.hasKey("allowsEditing")) {
      allowEditing = options.getBoolean("allowsEditing");
    }
    forceAngle = false;
    angle = 0;
    if (options.hasKey("angle")) {
      forceAngle = true;
      angle = options.getInt("angle");
    }
    pickVideo = false;
    if (options.hasKey("mediaType") && options.getString("mediaType").equals("video")) {
      pickVideo = true;
    }
    mimeFilter = "image/*";
    if(options.hasKey("mimeFilter")){
      mimeFilter = options.getString("mimeFilter");
    }
    videoQuality = 1;
    if (options.hasKey("videoQuality") && options.getString("videoQuality").equals("low")) {
      videoQuality = 0;
    }
    videoDurationLimit = 0;
    if (options.hasKey("durationLimit")) {
      videoDurationLimit = options.getInt("durationLimit");
    }
  }
}
