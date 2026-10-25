import 'dart:async';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:open_file/open_file.dart';
import '../services/recorder_service.dart';

class RecordingsScreen extends StatefulWidget {
  final RecorderService recorderService;

  const RecordingsScreen({super.key, required this.recorderService});

  @override
  State<RecordingsScreen> createState() => RecordingsScreenState();
}

class RecordingsScreenState extends State<RecordingsScreen> {
  List<FileSystemEntity> _files = [];
  bool _isLoading = true;
  StreamSubscription<RecorderEvent>? _eventSubscription;

  @override
  void initState() {
    super.initState();
    loadRecordings();

    // Auto-recarga cuando finaliza una grabación
    _eventSubscription = widget.recorderService.events.listen((event) {
      if (event.type == 'stopped') {
        loadRecordings();
      }
    });
  }

  @override
  void dispose() {
    _eventSubscription?.cancel();
    super.dispose();
  }

  Future<void> loadRecordings() async {
    if (!mounted) return;
    setState(() => _isLoading = true);
    try {
      final dirPath = await widget.recorderService.getRecordingsDirectory();
      final dir = Directory(dirPath);
      if (await dir.exists()) {
        final list = dir
            .listSync()
            .where((f) => f.path.endsWith('.mp4'))
            .toList()
          ..sort((a, b) => b.statSync().modified.compareTo(a.statSync().modified));
        if (mounted) {
          setState(() {
            _files = list;
            _isLoading = false;
          });
        }
      } else {
        if (mounted) {
          setState(() {
            _files = [];
            _isLoading = false;
          });
        }
      }
    } catch (_) {
      if (mounted) {
        setState(() => _isLoading = false);
      }
    }
  }

  Future<void> _deleteFile(File file) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: const Color(0xFF1E222D),
        title: const Text('Eliminar grabación', style: TextStyle(color: Colors.white)),
        content: const Text(
          '¿Deseas eliminar este video permanentemente?',
          style: TextStyle(color: Colors.white70),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('Cancelar', style: TextStyle(color: Colors.white60)),
          ),
          ElevatedButton(
            style: ElevatedButton.styleFrom(backgroundColor: Colors.redAccent),
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('Eliminar', style: TextStyle(color: Colors.white)),
          ),
        ],
      ),
    );

    if (confirmed == true) {
      try {
        await file.delete();
        loadRecordings();
      } catch (e) {
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text('Error al eliminar: $e')),
          );
        }
      }
    }
  }

  String _formatBytes(int bytes) {
    if (bytes < 1024) return '$bytes B';
    if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(1)} KB';
    return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF0F1117),
      appBar: AppBar(
        title: const Text('Grabaciones Guardadas'),
        backgroundColor: const Color(0xFF161922),
        elevation: 0,
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            tooltip: 'Actualizar lista',
            onPressed: loadRecordings,
          ),
        ],
      ),
      body: _isLoading
          ? const Center(child: CircularProgressIndicator(color: Colors.deepPurpleAccent))
          : _files.isEmpty
              ? Center(
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      Container(
                        padding: const EdgeInsets.all(20),
                        decoration: BoxDecoration(
                          color: Colors.white.withAlpha(10),
                          shape: BoxShape.circle,
                        ),
                        child: const Icon(
                          Icons.video_library_outlined,
                          size: 64,
                          color: Colors.white30,
                        ),
                      ),
                      const SizedBox(height: 16),
                      const Text(
                        'No hay grabaciones aún',
                        style: TextStyle(
                          color: Colors.white70,
                          fontSize: 18,
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                      const SizedBox(height: 8),
                      const Text(
                        'Tus videos guardados aparecerán aquí automáticamente',
                        style: TextStyle(color: Colors.white38, fontSize: 13),
                      ),
                    ],
                  ),
                )
              : ListView.separated(
                  padding: const EdgeInsets.all(16),
                  itemCount: _files.length,
                  separatorBuilder: (_, __) => const SizedBox(height: 12),
                  itemBuilder: (context, index) {
                    final file = File(_files[index].path);
                    final stat = file.statSync();
                    final dateStr =
                        DateFormat('dd/MM/yyyy HH:mm:ss').format(stat.modified);
                    final sizeStr = _formatBytes(stat.size);
                    final fileName = file.uri.pathSegments.last;

                    return Container(
                      decoration: BoxDecoration(
                        color: const Color(0xFF1A1D27),
                        borderRadius: BorderRadius.circular(16),
                        border: Border.all(color: Colors.white12),
                      ),
                      child: ListTile(
                        contentPadding: const EdgeInsets.symmetric(
                            horizontal: 16, vertical: 8),
                        leading: Container(
                          width: 48,
                          height: 48,
                          decoration: BoxDecoration(
                            color: Colors.deepPurpleAccent.withAlpha(40),
                            borderRadius: BorderRadius.circular(12),
                          ),
                          child: const Icon(
                            Icons.movie_outlined,
                            color: Colors.deepPurpleAccent,
                          ),
                        ),
                        title: Text(
                          fileName,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                            color: Colors.white,
                            fontWeight: FontWeight.w600,
                            fontSize: 14,
                          ),
                        ),
                        subtitle: Padding(
                          padding: const EdgeInsets.only(top: 4),
                          child: Text(
                            '$dateStr • $sizeStr',
                            style: const TextStyle(
                                color: Colors.white54, fontSize: 12),
                          ),
                        ),
                        trailing: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            IconButton(
                              icon: const Icon(Icons.play_circle_fill,
                                  color: Colors.greenAccent, size: 30),
                              tooltip: 'Reproducir',
                              onPressed: () => OpenFile.open(file.path),
                            ),
                            IconButton(
                              icon: const Icon(Icons.delete_outline,
                                  color: Colors.redAccent),
                              tooltip: 'Eliminar',
                              onPressed: () => _deleteFile(file),
                            ),
                          ],
                        ),
                        onTap: () => OpenFile.open(file.path),
                      ),
                    );
                  },
                ),
    );
  }
}
