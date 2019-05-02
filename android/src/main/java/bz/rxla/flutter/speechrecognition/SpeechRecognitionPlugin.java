package bz.rxla.flutter.speechrecognition;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.BroadcastReceiver;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry.Registrar;
import io.flutter.plugin.common.PluginRegistry.RequestPermissionsResultListener;

import java.util.ArrayList;
import java.util.Locale;

/**
 * SpeechRecognitionPlugin
 */
public class SpeechRecognitionPlugin implements MethodCallHandler, RecognitionListener {
	private static final String LOG_TAG = "SpeechRecognitionPlugin";

	private final Registrar registrar;
	private SpeechRecognizer speech;
	private MethodChannel speechChannel;
	String transcription = "";
	private boolean cancelled = false;
	private Intent recognizerIntent;
	private Activity activity;

	private final int GET_RECORD_AUDIO_PERMISSION = 65432;

	public static void registerWith(Registrar registrar) {
		final MethodChannel channel = new MethodChannel(registrar.messenger(), "speech_recognition");
		channel.setMethodCallHandler(new SpeechRecognitionPlugin(registrar, channel));
	}

	private SpeechRecognitionPlugin(Registrar registrar, MethodChannel channel) {
		this.speechChannel = channel;
		this.speechChannel.setMethodCallHandler(this);
		this.activity = registrar.activity();
		this.registrar = registrar;

		speech = SpeechRecognizer.createSpeechRecognizer(activity.getApplicationContext());
		speech.setRecognitionListener(this);

		recognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
		recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
		recognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
		recognizerIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);

		registrar.addRequestPermissionsResultListener(
			new RequestPermissionsResultListener() {
				@Override
				public boolean onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
					switch (requestCode) {
						case GET_RECORD_AUDIO_PERMISSION:
							if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
								// granted
								speechChannel.invokeMethod("speech.onPermissionGranted", null);
								return true;
							} else {
								// denied
								speechChannel.invokeMethod("speech.onPermissionDenied", null);
								return false;
							}
					}

					return false;
				}
			}
		);
	}

	@Override
	public void onMethodCall(MethodCall call, Result result) {
		switch(call.method) {
			case "speech.activate":
				Context context = registrar.context();
				PackageManager pm = context.getPackageManager();
				int hasRecordPerm = pm.checkPermission(Manifest.permission.RECORD_AUDIO, context.getPackageName());
				boolean hasPermissions = hasRecordPerm == PackageManager.PERMISSION_GRANTED;

				if (!hasPermissions) {
					this.activity.requestPermissions(
						new String[]{Manifest.permission.RECORD_AUDIO},
						GET_RECORD_AUDIO_PERMISSION
					);
					hasPermissions = hasRecordPerm == PackageManager.PERMISSION_GRANTED;
				}

				Log.d(LOG_TAG, "Application has permissions: " + hasPermissions);
				result.success(hasPermissions);
				if (hasPermissions) {
					speechChannel.invokeMethod("speech.onPermissionGranted", null);
				}

				Locale locale = activity.getResources().getConfiguration().locale;
				Log.d(LOG_TAG, "Current Locale: " + locale.toString());
				speechChannel.invokeMethod("speech.onCurrentLocale", locale.toString());
				break;

			case "speech.listen":
				recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, getLocale(call.arguments.toString()));
				cancelled = false;
				speech.startListening(recognizerIntent);
				result.success(true);
				break;

			case "speech.cancel":
				speech.stopListening();
				cancelled = true;
				result.success(true);
				break;

			case "speech.stop":
				speech.stopListening();
				cancelled = false;
				result.success(true);
				break;

			case "speech.supportedLocales":
				getSupportedLocales(this.activity, result);
			  break;

			default:
				result.notImplemented();
			}
	}

	private Locale getLocale(String code) {
		String[] localeParts = code.split("_");
		return new Locale(localeParts[0], localeParts[1]);
	}

	@Override
	public void onReadyForSpeech(Bundle params) {
		Log.d(LOG_TAG, "onReadyForSpeech");
		speechChannel.invokeMethod("speech.onSpeechAvailability", true);
	}

	@Override
	public void onBeginningOfSpeech() {
		Log.d(LOG_TAG, "onRecognitionStarted");
		transcription = "";

		speechChannel.invokeMethod("speech.onRecognitionStarted", null);
	}

	@Override
	public void onRmsChanged(float rmsdB) {
		Log.d(LOG_TAG, "onRmsChanged: " + rmsdB);
	}
	

	@Override
	public void onBufferReceived(byte[] buffer) {
		Log.d(LOG_TAG, "onBufferReceived");
	}

	@Override
	public void onEndOfSpeech() {
		Log.d(LOG_TAG, "onEndOfSpeech");
	}

	@Override
	public void onError(int error) {
		Log.d(LOG_TAG, "onError: " + error);
		speechChannel.invokeMethod("speech.onSpeechAvailability", false);
		speechChannel.invokeMethod("speech.onError", error);
	}

	@Override
	public void onPartialResults(Bundle partialResults) {
		Log.d(LOG_TAG, "onPartialResults...");
		ArrayList<String> matches = partialResults
			.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
		transcription = matches.get(0);
		sendTranscription(false);

	}

	@Override
	public void onEvent(int eventType, Bundle params) {
		Log.d(LOG_TAG, "onEvent: " + eventType);
	}

	@Override
	public void onResults(Bundle results) {
		Log.d(LOG_TAG, "onResults...");
		ArrayList<String> matches = results
			.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
		String text = "";
		transcription = matches.get(0);
		Log.d(LOG_TAG, "onResults -> " + transcription);
		sendTranscription(true);
	}

	private void sendTranscription(boolean isFinal) {
		speechChannel.invokeMethod(isFinal ? "speech.onRecognitionComplete" : "speech.onSpeech", transcription);
	}

	private void getSupportedLocales(final Activity activity, final Result result) {
		final Intent recognizerIntent = new Intent(RecognizerIntent.ACTION_GET_LANGUAGE_DETAILS);
		recognizerIntent.setPackage("com.google.android.googlequicksearchbox");

		activity.sendOrderedBroadcast(recognizerIntent, null, new BroadcastReceiver() {
			@Override
			public void onReceive(final Context context, final Intent intent) {
				final Bundle bundle = getResultExtras(true);
				ArrayList<String> locales = null;

				if (bundle != null) {
					if (bundle.containsKey(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES)) {
						locales = bundle.getStringArrayList(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES);
					}
				}
				if (locales != null) {
					result.success(locales);
				} else {
					result.error("Can not get supported locales.", null, null);
				}
			}
		}, null, 1234, null, null);
	}
}
