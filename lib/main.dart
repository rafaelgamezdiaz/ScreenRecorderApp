import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'screens/home_screen.dart';
import 'screens/recordings_screen.dart';
import 'services/recorder_service.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setPreferredOrientations([
    DeviceOrientation.portraitUp,
    DeviceOrientation.portraitDown,
  ]);
  runApp(const ScreenRecorderApp());
}

class ScreenRecorderApp extends StatefulWidget {
  const ScreenRecorderApp({super.key});

  @override
  State<ScreenRecorderApp> createState() => _ScreenRecorderAppState();
}

class _ScreenRecorderAppState extends State<ScreenRecorderApp> {
  final RecorderService _recorderService = RecorderService();
  final GlobalKey<RecordingsScreenState> _recordingsKey =
      GlobalKey<RecordingsScreenState>();
  int _currentIndex = 0;

  @override
  void dispose() {
    _recorderService.dispose();
    super.dispose();
  }

  void _navigateToRecordings() {
    setState(() => _currentIndex = 1);
    _recordingsKey.currentState?.loadRecordings();
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Screen & Audio Recorder',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        brightness: Brightness.dark,
        scaffoldBackgroundColor: const Color(0xFF0F1117),
        primaryColor: Colors.deepPurpleAccent,
        colorScheme: const ColorScheme.dark(
          primary: Colors.deepPurpleAccent,
          secondary: Colors.tealAccent,
          surface: Color(0xFF161922),
        ),
        useMaterial3: true,
      ),
      home: Scaffold(
        body: IndexedStack(
          index: _currentIndex,
          children: [
            HomeScreen(
              recorderService: _recorderService,
              onNavigateToRecordings: _navigateToRecordings,
            ),
            RecordingsScreen(
              key: _recordingsKey,
              recorderService: _recorderService,
            ),
          ],
        ),
        bottomNavigationBar: NavigationBar(
          backgroundColor: const Color(0xFF161924),
          indicatorColor: Colors.deepPurpleAccent.withAlpha(60),
          selectedIndex: _currentIndex,
          onDestinationSelected: (idx) {
            setState(() => _currentIndex = idx);
            if (idx == 1) {
              _recordingsKey.currentState?.loadRecordings();
            }
          },
          destinations: const [
            NavigationDestination(
              icon: Icon(Icons.videocam_outlined),
              selectedIcon: Icon(Icons.videocam, color: Colors.deepPurpleAccent),
              label: 'Grabar',
            ),
            NavigationDestination(
              icon: Icon(Icons.video_library_outlined),
              selectedIcon: Icon(Icons.video_library, color: Colors.deepPurpleAccent),
              label: 'Grabaciones',
            ),
          ],
        ),
      ),
    );
  }
}
