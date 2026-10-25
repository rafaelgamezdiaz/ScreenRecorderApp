import 'package:flutter_test/flutter_test.dart';
import 'package:screen_recorder_gamez/main.dart';

void main() {
  testWidgets('ScreenRecorderApp smoke test', (WidgetTester tester) async {
    await tester.pumpWidget(const ScreenRecorderApp());
    expect(find.text('Grabador de Pantalla & Audio'), findsOneWidget);
  });
}
