import traceback
import sys

try:
    from kivy.app import App
    from kivy.uix.label import Label
    from kivy.uix.scrollview import ScrollView
    from kivy.uix.boxlayout import BoxLayout

    class IRISApp(App):
        def build(self):
            scroll = ScrollView()
            layout = BoxLayout(orientation='vertical', padding=20, size_hint_y=None)
            layout.bind(minimum_height=layout.setter('height'))

            lines = ["=== IRIS DIAGNOSTIC ===\n"]

            for mod in ['plyer', 'kivy', 'android', 'jnius']:
                try:
                    __import__(mod)
                    lines.append(f"✓ {mod}")
                except Exception as e:
                    lines.append(f"✗ {mod}: {e}")

            try:
                from iris_core import main as real_main
                lines.append("\n✓ iris_core imported OK")
            except Exception as e:
                lines.append(f"\n✗ iris_core: {traceback.format_exc()}")

            label = Label(
                text="\n".join(lines),
                font_size='14sp',
                halign='left',
                valign='top',
                size_hint_y=None,
                text_size=(None, None)
            )
            label.bind(texture_size=label.setter('size'))
            layout.add_widget(label)
            scroll.add_widget(layout)
            return scroll

    IRISApp().run()

except Exception as e:
    pass
