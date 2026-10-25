import 'dart:async';
import 'dart:io';
import 'package:flutter/services.dart';
import 'package:path_provider/path_provider.dart';
import 'package:permission_handler/permission_handler.dart';

enum RecordingState { idle, starting, recording, stopping }

class RecorderEvent {
  final String type;
  final int? seconds;
  final String? path;
  final String? message;

  RecorderEvent({
    required this.type,
    this.seconds,
    this.path,
    this.message,
  });
}

class RecorderService {
  static const MethodChannel _methodChannel =
      MethodChannel('com.screenrecorder.app/recorder');
  static const EventChannel _eventChannel =
      EventChannel('com.screenrecorder.app/recorder_events');

  final StreamController<RecorderEvent> _eventController =
      StreamController<RecorderEvent>.broadcast();

  Stream<RecorderEvent> get events => _eventController.stream;
  StreamSubscription? _eventSubscription;

  RecorderService() {
    _eventSubscription = _eventChannel.receiveBroadcastStream().listen(
      (dynamic data) {
        if (data is Map) {
          final event = data['event'] as String?;
          if (event == 'onStarted') {
            _eventController.add(RecorderEvent(type: 'started'));
          } else if (event == 'onStopped') {
            _eventController.add(RecorderEvent(
              type: 'stopped',
              path: data['path'] as String?,
            ));
          } else if (event == 'onDuration') {
            _eventController.add(RecorderEvent(
              type: 'duration',
              seconds: (data['seconds'] as num?)?.toInt(),
            ));
          } else if (event == 'onError') {
            _eventController.add(RecorderEvent(
              type: 'error',
              message: data['message'] as String?,
            ));
          }
        }
      },
      onError: (error) {
        _eventController.add(RecorderEvent(
          type: 'error',
          message: error.toString(),
        ));
      },
    );
  }

  Future<bool> checkAndRequestPermissions({required bool recordMic}) async {
    if (recordMic) {
      final micStatus = await Permission.microphone.request();
      if (!micStatus.isGranted) {
        return false;
      }
    }

    final notifStatus = await Permission.notification.request();
    if (!notifStatus.isGranted && !notifStatus.isLimited) {
      // Notificaciones opcionales en versiones previas pero recomendadas
    }

    return true;
  }

  Future<String> getRecordingsDirectory() async {
    final dir = await getExternalStorageDirectory();
    final recordingsDir = Directory('${dir?.path}/Recordings');
    if (!await recordingsDir.exists()) {
      await recordingsDir.create(recursive: true);
    }
    return recordingsDir.path;
  }

  Future<bool> startRecording({
    required bool recordMic,
    required bool recordInternal,
    int? width,
    int? height,
    int fps = 30,
    int bitrate = 6000000,
  }) async {
    final permissionsGranted =
        await checkAndRequestPermissions(recordMic: recordMic);
    if (!permissionsGranted) {
      throw Exception('Permisos requeridos denegados (micrófono)');
    }

    final recordingsDir = await getRecordingsDirectory();
    final timestamp = DateTime.now().millisecondsSinceEpoch;
    final outputPath = '$recordingsDir/REC_$timestamp.mp4';

    final result = await _methodChannel.invokeMethod<bool>(
      'startRecording',
      {
        'recordMic': recordMic,
        'recordInternal': recordInternal,
        'width': width,
        'height': height,
        'fps': fps,
        'bitrate': bitrate,
        'outputPath': outputPath,
      },
    );

    return result ?? false;
  }

  Future<bool> stopRecording() async {
    final result = await _methodChannel.invokeMethod<bool>('stopRecording');
    return result ?? false;
  }

  Future<bool> isRecording() async {
    final result = await _methodChannel.invokeMethod<bool>('isRecording');
    return result ?? false;
  }

  void dispose() {
    _eventSubscription?.cancel();
    _eventController.close();
  }
}
