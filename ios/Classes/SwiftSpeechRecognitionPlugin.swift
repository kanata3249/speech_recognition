import Flutter
import UIKit
import Speech

@available(iOS 10.0, *)
public class SwiftSpeechRecognitionPlugin: NSObject, FlutterPlugin, SFSpeechRecognizerDelegate {
	public static func register(with registrar: FlutterPluginRegistrar) {
		let channel = FlutterMethodChannel(name: "speech_recognition", binaryMessenger: registrar.messenger())
		let instance = SwiftSpeechRecognitionPlugin(channel: channel)
		registrar.addMethodCallDelegate(instance, channel: channel)
	}

	private var lang: String = "en_US"
	private var speechRecognizer = SFSpeechRecognizer(locale: Locale(identifier: "en_US"))!

	private var speechChannel: FlutterMethodChannel?

	private var recognitionRequest: SFSpeechAudioBufferRecognitionRequest?

	private var recognitionTask: SFSpeechRecognitionTask?

	private let audioEngine = AVAudioEngine()

	private var timer: Timer?

	init(channel:FlutterMethodChannel){
		speechChannel = channel
		super.init()
	}

	public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
		//result("iOS " + UIDevice.current.systemVersion)
		switch (call.method) {
		case "speech.activate":
			self.activateRecognition(result: result)
		case "speech.listen":
			self.startRecognition(lang: call.arguments as! String, result: result)
		case "speech.cancel":
			self.cancelRecognition(result: result)
		case "speech.stop":
			self.stopRecognition(result: result)
		case "speech.supportedLocales":
			self.supportedLocales(result: result)
		default:
			result(FlutterMethodNotImplemented)
		}
	}

	private func activateRecognition(result: @escaping FlutterResult) {
		speechRecognizer.delegate = self

		SFSpeechRecognizer.requestAuthorization { authStatus in
			OperationQueue.main.addOperation {
				switch authStatus {
				case .authorized:
					result(true)
					self.speechChannel?.invokeMethod("speech.onPermissionGranted", arguments: nil)
					self.speechChannel?.invokeMethod("speech.onCurrentLocale", arguments: Locale.preferredLanguages.first)

				case .denied:
					result(false)
					self.speechChannel?.invokeMethod("speech.onPermissionDenied", arguments: nil)

				case .restricted:
					result(false)
					self.speechChannel?.invokeMethod("speech.onPermissionDenied", arguments: nil)

				case .notDetermined:
					result(false)
					self.speechChannel?.invokeMethod("speech.onPermissionDenied", arguments: nil)
				}
				print("SFSpeechRecognizer.requestAuthorization \(authStatus.rawValue)")
			}
		}
	}

	private func startRecognition(lang: String, result: FlutterResult) {
		print("startRecognition...")
		if audioEngine.isRunning {
			audioEngine.stop()
			recognitionRequest?.endAudio()
			result(false)
		} else {
			try! start(lang: lang)
			result(true)
		}
	}

	private func cancelRecognition(result: FlutterResult?) {
		if let recognitionTask = recognitionTask {
			recognitionTask.cancel()
			self.recognitionTask = nil
			if let r = result {
				r(false)
			}
		}
	}

	private func stopRecognition(result: FlutterResult) {
		if audioEngine.isRunning {
			audioEngine.stop()
			recognitionRequest?.endAudio()
		}
		result(false)
	}

	private func startAutoStopTimer() {
		timer?.invalidate()
		timer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: false, block: { (timer) in
			if self.audioEngine.isRunning {
				self.audioEngine.stop()
				self.recognitionRequest?.endAudio()
			}
		})
	}

	private func start(lang: String) throws {

		cancelRecognition(result: nil)

		let audioSession = AVAudioSession.sharedInstance()
		try audioSession.setCategory(AVAudioSessionCategoryRecord)
		try audioSession.setMode(AVAudioSessionModeMeasurement)
		try audioSession.setActive(true, with: .notifyOthersOnDeactivation)

		recognitionRequest = SFSpeechAudioBufferRecognitionRequest()

		let inputNode = audioEngine.inputNode
		guard let recognitionRequest = recognitionRequest else {
			fatalError("Unable to created a SFSpeechAudioBufferRecognitionRequest object")
		}

		recognitionRequest.shouldReportPartialResults = true

		let speechRecognizer = getRecognizer(lang: lang)

		recognitionTask = speechRecognizer.recognitionTask(with: recognitionRequest) { result, error in
			var isFinal = false

			if let result = result {
				print("Speech : \(result.bestTranscription.formattedString)")
				self.speechChannel?.invokeMethod("speech.onSpeech", arguments: result.bestTranscription.formattedString)
				isFinal = result.isFinal
				if isFinal {
					self.timer?.invalidate()
					self.speechChannel!.invokeMethod(
						"speech.onRecognitionComplete",
						arguments: result.bestTranscription.formattedString
					)
				} else {
					self.startAutoStopTimer()
				}
			}

			if error != nil || isFinal {
				self.audioEngine.stop()
				inputNode.removeTap(onBus: 0)
				self.recognitionRequest = nil
				self.recognitionTask = nil
			}
		}

		let recognitionFormat = inputNode.outputFormat(forBus: 0)
		inputNode.installTap(onBus: 0, bufferSize: 1024, format: recognitionFormat) {
			(buffer: AVAudioPCMBuffer, when: AVAudioTime) in
			self.recognitionRequest?.append(buffer)
		}

		audioEngine.prepare()
		try audioEngine.start()

		speechChannel!.invokeMethod("speech.onRecognitionStarted", arguments: nil)
	}

	private func getRecognizer(lang: String) -> Speech.SFSpeechRecognizer {
		if (lang != self.lang) {
			self.lang = lang;
			speechRecognizer = SFSpeechRecognizer(locale: Locale(identifier: lang))!
			speechRecognizer.delegate = self
		}
		return speechRecognizer
	}

	private func supportedLocales(result: FlutterResult) {
		let localeSet = SFSpeechRecognizer.supportedLocales()

		result(
			localeSet.map{
				if ($0.languageCode != nil && $0.regionCode != nil) {
					return "\($0.languageCode!)_\($0.regionCode!)"
				} else {
					return "<invalid>"
				}
			}.filter { $0 != "<invalid>" }
		)
	}

	public func speechRecognizer(_ speechRecognizer: SFSpeechRecognizer, availabilityDidChange available: Bool) {
		if available {
			speechChannel?.invokeMethod("speech.onSpeechAvailability", arguments: true)
		} else {
			speechChannel?.invokeMethod("speech.onSpeechAvailability", arguments: false)
		}
	}
}
