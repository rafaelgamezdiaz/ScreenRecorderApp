import 'dart:async';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:open_file/open_file.dart';
import '../services/recorder_service.dart';

class HomeScreen extends StatefulWidget {
  final RecorderService recorderService;
  final VoidCallback onNavigateToRecordings;

  const HomeScreen({
    super.key,
    required this.recorderService,
    required this.onNavigateToRecordings,
  });

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen>
    with SingleTickerProviderStateMixin {
  bool _recordInternal = true;
  bool _recordMic = true;
  String _selectedResolution = '1080p';
  int _selectedFps = 30;
  int _selectedBitrate = 6000000;

  bool _isRecording = false;
  int _durationSeconds = 0;
  StreamSubscription<RecorderEvent>? _eventSubscription;

  late AnimationController _pulseController;

  @override
  void initState() {
    super.initState();
    _pulseController = AnimationController(
      vsync: this,
      duration: const Duration(seconds: 1),
    )..repeat(reverse: true);

    _initRecorderState();
  }

  Future<void> _initRecorderState() async {
    final active = await widget.recorderService.isRecording();
    setState(() => _isRecording = active);

    _eventSubscription = widget.recorderService.events.listen((event) {
      if (event.type == 'started') {
        setState(() {
          _isRecording = true;
          _durationSeconds = 0;
        });
      } else if (event.type == 'stopped') {
        final totalDuration = _durationSeconds;
        setState(() {
          _isRecording = false;
          _durationSeconds = 0;
        });
        if (mounted && event.path != null) {
          _showRecordingCompletedModal(event.path!, totalDuration);
        }
      } else if (event.type == 'duration') {
        setState(() {
          _durationSeconds = event.seconds ?? _durationSeconds;
        });
      } else if (event.type == 'error') {
        setState(() => _isRecording = false);
        if (mounted && event.message != null) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(
              backgroundColor: Colors.redAccent,
              content: Text(event.message!),
            ),
          );
        }
      }
    });
  }

  @override
  void dispose() {
    _eventSubscription?.cancel();
    _pulseController.dispose();
    super.dispose();
  }

  String _formatTimer(int totalSeconds) {
    final minutes = (totalSeconds ~/ 60).toString().padLeft(2, '0');
    final seconds = (totalSeconds % 60).toString().padLeft(2, '0');
    final hours = (totalSeconds ~/ 3600);
    if (hours > 0) {
      return '$hours:$minutes:$seconds';
    }
    return '$minutes:$seconds';
  }

  String _formatBytes(int bytes) {
    if (bytes < 1024) return '$bytes B';
    if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(1)} KB';
    return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
  }

  void _showRecordingCompletedModal(String filePath, int durationSeconds) {
    final file = File(filePath);
    int fileSize = 0;
    try {
      if (file.existsSync()) {
        fileSize = file.lengthSync();
      }
    } catch (_) {}

    final fileName = file.uri.pathSegments.isNotEmpty
        ? file.uri.pathSegments.last
        : 'grabacion.mp4';

    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (ctx) => Container(
        padding: const EdgeInsets.fromLTRB(24, 12, 24, 32),
        decoration: const BoxDecoration(
          color: Color(0xFF161924),
          borderRadius: BorderRadius.vertical(top: Radius.circular(28)),
          boxShadow: [
            BoxShadow(
              color: Colors.black54,
              blurRadius: 20,
              offset: Offset(0, -4),
            ),
          ],
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            // Handle bar
            Container(
              width: 44,
              height: 4,
              margin: const EdgeInsets.only(bottom: 24),
              decoration: BoxDecoration(
                color: Colors.white24,
                borderRadius: BorderRadius.circular(2),
              ),
            ),

            // Header Icon
            Container(
              width: 64,
              height: 64,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                gradient: const LinearGradient(
                  colors: [Colors.deepPurpleAccent, Color(0xFF4C1D95)],
                  begin: Alignment.topLeft,
                  end: Alignment.bottomRight,
                ),
                boxShadow: [
                  BoxShadow(
                    color: Colors.deepPurpleAccent.withAlpha(80),
                    blurRadius: 16,
                    offset: const Offset(0, 4),
                  ),
                ],
              ),
              child: const Icon(
                Icons.check_rounded,
                color: Colors.white,
                size: 38,
              ),
            ),

            const SizedBox(height: 16),

            const Text(
              '¡Grabación Finalizada!',
              style: TextStyle(
                color: Colors.white,
                fontSize: 22,
                fontWeight: FontWeight.bold,
              ),
            ),

            const SizedBox(height: 6),

            const Text(
              'El video fue codificado y guardado con éxito.',
              textAlign: TextAlign.center,
              style: TextStyle(color: Colors.white60, fontSize: 13),
            ),

            const SizedBox(height: 20),

            // Details card
            Container(
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: const Color(0xFF1E2230),
                borderRadius: BorderRadius.circular(16),
                border: Border.all(color: Colors.white12),
              ),
              child: Column(
                children: [
                  Row(
                    children: [
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            const Text(
                              'DURACIÓN',
                              style: TextStyle(
                                color: Colors.white38,
                                fontSize: 11,
                                fontWeight: FontWeight.bold,
                                letterSpacing: 0.8,
                              ),
                            ),
                            const SizedBox(height: 4),
                            Text(
                              _formatTimer(durationSeconds),
                              style: const TextStyle(
                                color: Colors.white,
                                fontSize: 16,
                                fontWeight: FontWeight.w600,
                                fontFamily: 'monospace',
                              ),
                            ),
                          ],
                        ),
                      ),
                      Container(width: 1, height: 32, color: Colors.white12),
                      Expanded(
                        child: Padding(
                          padding: const EdgeInsets.only(left: 16),
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              const Text(
                                'TAMAÑO',
                                style: TextStyle(
                                  color: Colors.white38,
                                  fontSize: 11,
                                  fontWeight: FontWeight.bold,
                                  letterSpacing: 0.8,
                                ),
                              ),
                              const SizedBox(height: 4),
                              Text(
                                _formatBytes(fileSize),
                                style: const TextStyle(
                                  color: Colors.white,
                                  fontSize: 16,
                                  fontWeight: FontWeight.w600,
                                ),
                              ),
                            ],
                          ),
                        ),
                      ),
                    ],
                  ),
                  const Divider(color: Colors.white10, height: 24),
                  Row(
                    children: [
                      const Icon(Icons.description_outlined,
                          color: Colors.deepPurpleAccent, size: 20),
                      const SizedBox(width: 10),
                      Expanded(
                        child: Text(
                          fileName,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                            color: Colors.white70,
                            fontSize: 13,
                          ),
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ),

            const SizedBox(height: 24),

            // Action buttons
            SizedBox(
              width: double.infinity,
              height: 50,
              child: ElevatedButton.icon(
                style: ElevatedButton.styleFrom(
                  backgroundColor: Colors.deepPurpleAccent,
                  foregroundColor: Colors.white,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(14),
                  ),
                  elevation: 4,
                ),
                icon: const Icon(Icons.play_circle_fill, size: 22),
                label: const Text(
                  'REPRODUCIR AHORA',
                  style: TextStyle(
                    fontWeight: FontWeight.bold,
                    letterSpacing: 1.1,
                  ),
                ),
                onPressed: () {
                  Navigator.pop(ctx);
                  OpenFile.open(filePath);
                },
              ),
            ),

            const SizedBox(height: 12),

            SizedBox(
              width: double.infinity,
              height: 48,
              child: OutlinedButton.icon(
                style: OutlinedButton.styleFrom(
                  foregroundColor: Colors.white,
                  side: const BorderSide(color: Colors.white24),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(14),
                  ),
                ),
                icon: const Icon(Icons.video_library_outlined, size: 20),
                label: const Text(
                  'Ir a Grabaciones Guardadas',
                  style: TextStyle(fontWeight: FontWeight.w600),
                ),
                onPressed: () {
                  Navigator.pop(ctx);
                  widget.onNavigateToRecordings();
                },
              ),
            ),

            const SizedBox(height: 6),

            TextButton(
              onPressed: () => Navigator.pop(ctx),
              child: const Text(
                'Cerrar',
                style: TextStyle(color: Colors.white54),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _toggleRecording() async {
    if (_isRecording) {
      await widget.recorderService.stopRecording();
    } else {
      int? width;
      int? height;
      if (_selectedResolution == '1080p') {
        width = 1080;
        height = 1920;
      } else if (_selectedResolution == '720p') {
        width = 720;
        height = 1280;
      }

      try {
        await widget.recorderService.startRecording(
          recordMic: _recordMic,
          recordInternal: _recordInternal,
          width: width,
          height: height,
          fps: _selectedFps,
          bitrate: _selectedBitrate,
        );
      } catch (e) {
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(
              backgroundColor: Colors.redAccent,
              content: Text('Error: $e'),
            ),
          );
        }
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF0F1117),
      appBar: AppBar(
        title: const Text('Grabador de Pantalla & Audio'),
        backgroundColor: const Color(0xFF161922),
        elevation: 0,
        actions: [
          IconButton(
            icon: const Icon(Icons.folder_special_outlined),
            onPressed: widget.onNavigateToRecordings,
            tooltip: 'Ver Grabaciones',
          ),
        ],
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // Status and Timer Card
            Container(
              padding: const EdgeInsets.symmetric(vertical: 28, horizontal: 20),
              decoration: BoxDecoration(
                gradient: LinearGradient(
                  colors: _isRecording
                      ? [const Color(0xFF7F1D1D), const Color(0xFF450A0A)]
                      : [const Color(0xFF1E2230), const Color(0xFF141724)],
                  begin: Alignment.topLeft,
                  end: Alignment.bottomRight,
                ),
                borderRadius: BorderRadius.circular(20),
                border: Border.all(
                  color: _isRecording ? Colors.redAccent : Colors.white12,
                  width: 1.5,
                ),
              ),
              child: Column(
                children: [
                  Row(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      if (_isRecording)
                        FadeTransition(
                          opacity: _pulseController,
                          child: Container(
                            width: 12,
                            height: 12,
                            decoration: const BoxDecoration(
                              color: Colors.redAccent,
                              shape: BoxShape.circle,
                            ),
                          ),
                        )
                      else
                        Container(
                          width: 12,
                          height: 12,
                          decoration: const BoxDecoration(
                            color: Colors.greenAccent,
                            shape: BoxShape.circle,
                          ),
                        ),
                      const SizedBox(width: 8),
                      Text(
                        _isRecording ? 'GRABANDO EN VIVO' : 'LISTO PARA GRABAR',
                        style: TextStyle(
                          color: _isRecording ? Colors.redAccent : Colors.greenAccent,
                          fontWeight: FontWeight.bold,
                          letterSpacing: 1.2,
                          fontSize: 13,
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 16),
                  Text(
                    _formatTimer(_durationSeconds),
                    style: const TextStyle(
                      color: Colors.white,
                      fontSize: 48,
                      fontWeight: FontWeight.w700,
                      fontFamily: 'monospace',
                    ),
                  ),
                ],
              ),
            ),

            const SizedBox(height: 24),

            // Audio configuration card
            Container(
              padding: const EdgeInsets.all(18),
              decoration: BoxDecoration(
                color: const Color(0xFF161924),
                borderRadius: BorderRadius.circular(16),
                border: Border.all(color: Colors.white12),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text(
                    'FUENTES DE AUDIO',
                    style: TextStyle(
                      color: Colors.white70,
                      fontWeight: FontWeight.bold,
                      fontSize: 12,
                      letterSpacing: 1,
                    ),
                  ),
                  const SizedBox(height: 12),
                  SwitchListTile(
                    contentPadding: EdgeInsets.zero,
                    activeColor: Colors.deepPurpleAccent,
                    title: const Text('Audio Interno del Teléfono',
                        style: TextStyle(color: Colors.white)),
                    subtitle: const Text(
                      'Captura sonidos de juegos, apps y multimedia',
                      style: TextStyle(color: Colors.white54, fontSize: 12),
                    ),
                    value: _recordInternal,
                    onChanged: _isRecording
                        ? null
                        : (v) => setState(() => _recordInternal = v),
                  ),
                  const Divider(color: Colors.white10),
                  SwitchListTile(
                    contentPadding: EdgeInsets.zero,
                    activeColor: Colors.deepPurpleAccent,
                    title: const Text('Micrófono Externo',
                        style: TextStyle(color: Colors.white)),
                    subtitle: const Text(
                      'Captura tu voz y narración al explicar pasos',
                      style: TextStyle(color: Colors.white54, fontSize: 12),
                    ),
                    value: _recordMic,
                    onChanged: _isRecording
                        ? null
                        : (v) => setState(() => _recordMic = v),
                  ),
                ],
              ),
            ),

            const SizedBox(height: 20),

            // Video settings card
            Container(
              padding: const EdgeInsets.all(18),
              decoration: BoxDecoration(
                color: const Color(0xFF161924),
                borderRadius: BorderRadius.circular(16),
                border: Border.all(color: Colors.white12),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text(
                    'CALIDAD DE VIDEO',
                    style: TextStyle(
                      color: Colors.white70,
                      fontWeight: FontWeight.bold,
                      fontSize: 12,
                      letterSpacing: 1,
                    ),
                  ),
                  const SizedBox(height: 12),
                  Row(
                    children: [
                      Expanded(
                        child: DropdownButtonFormField<String>(
                          value: _selectedResolution,
                          dropdownColor: const Color(0xFF1E2230),
                          style: const TextStyle(color: Colors.white),
                          decoration: const InputDecoration(
                            labelText: 'Resolución',
                            labelStyle: TextStyle(color: Colors.white60),
                            border: OutlineInputBorder(),
                          ),
                          items: const [
                            DropdownMenuItem(value: '1080p', child: Text('1080p FHD')),
                            DropdownMenuItem(value: '720p', child: Text('720p HD')),
                          ],
                          onChanged: _isRecording
                              ? null
                              : (v) => setState(() => _selectedResolution = v!),
                        ),
                      ),
                      const SizedBox(width: 12),
                      Expanded(
                        child: DropdownButtonFormField<int>(
                          value: _selectedFps,
                          dropdownColor: const Color(0xFF1E2230),
                          style: const TextStyle(color: Colors.white),
                          decoration: const InputDecoration(
                            labelText: 'Cuadros/Seg',
                            labelStyle: TextStyle(color: Colors.white60),
                            border: OutlineInputBorder(),
                          ),
                          items: const [
                            DropdownMenuItem(value: 30, child: Text('30 FPS')),
                            DropdownMenuItem(value: 60, child: Text('60 FPS')),
                          ],
                          onChanged: _isRecording
                              ? null
                              : (v) => setState(() => _selectedFps = v!),
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 12),
                  DropdownButtonFormField<int>(
                    value: _selectedBitrate,
                    dropdownColor: const Color(0xFF1E2230),
                    style: const TextStyle(color: Colors.white),
                    decoration: const InputDecoration(
                      labelText: 'Bitrate de Video',
                      labelStyle: TextStyle(color: Colors.white60),
                      border: OutlineInputBorder(),
                    ),
                    items: const [
                      DropdownMenuItem(value: 4000000, child: Text('4 Mbps (Bajo)')),
                      DropdownMenuItem(value: 6000000, child: Text('6 Mbps (Estándar)')),
                      DropdownMenuItem(value: 8000000, child: Text('8 Mbps (Alta Calidad)')),
                      DropdownMenuItem(value: 12000000, child: Text('12 Mbps (Máxima)')),
                    ],
                    onChanged: _isRecording
                        ? null
                        : (v) => setState(() => _selectedBitrate = v!),
                  ),
                ],
              ),
            ),

            const SizedBox(height: 32),

            // Big Start/Stop button
            SizedBox(
              height: 56,
              child: ElevatedButton.icon(
                style: ElevatedButton.styleFrom(
                  backgroundColor:
                      _isRecording ? Colors.redAccent : Colors.deepPurpleAccent,
                  foregroundColor: Colors.white,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(16),
                  ),
                  elevation: 6,
                ),
                icon: Icon(
                  _isRecording ? Icons.stop : Icons.fiber_manual_record,
                  size: 26,
                ),
                label: Text(
                  _isRecording ? 'DETENER GRABACIÓN' : 'INICIAR GRABACIÓN',
                  style: const TextStyle(
                    fontSize: 16,
                    fontWeight: FontWeight.bold,
                    letterSpacing: 1.1,
                  ),
                ),
                onPressed: _toggleRecording,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
