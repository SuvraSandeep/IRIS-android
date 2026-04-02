from kivy.app import App
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.button import Button
from kivy.uix.label import Label
from kivy.uix.scrollview import ScrollView
from kivy.graphics import Color, RoundedRectangle
from kivy.clock import Clock
from kivy.core.window import Window

Window.clearcolor = (0.05, 0.05, 0.1, 1)  # Dark navy background


class RoundedButton(Button):
    pass


class JarvisUI(BoxLayout):
    def __init__(self, **kwargs):
        super().__init__(orientation='vertical', padding=20, spacing=15, **kwargs)
        self.is_active = False
        self.log_lines = []
        self._build_ui()

    def _build_ui(self):
        # --- Title ---
        title = Label(
            text="J.A.R.V.I.S",
            font_size=36,
            bold=True,
            color=(0.0, 0.8, 1.0, 1),
            size_hint=(1, 0.12)
        )
        self.add_widget(title)

        # --- Status Indicator ---
        self.status_label = Label(
            text="● IDLE",
            font_size=20,
            color=(0.6, 0.6, 0.6, 1),
            size_hint=(1, 0.08)
        )
        self.add_widget(self.status_label)

        # --- Toggle Button ---
        self.toggle_btn = Button(
            text="ACTIVATE",
            font_size=22,
            bold=True,
            size_hint=(0.7, 0.12),
            pos_hint={"center_x": 0.5},
            background_normal='',
            background_color=(0.0, 0.6, 0.9, 1),
            color=(1, 1, 1, 1)
        )
        self.toggle_btn.bind(on_press=self.toggle_jarvis)
        self.add_widget(self.toggle_btn)

        # --- Log Label ---
        log_title = Label(
            text="Command Log",
            font_size=16,
            color=(0.5, 0.5, 0.7, 1),
            size_hint=(1, 0.06),
            halign='left',
            text_size=(Window.width - 40, None)
        )
        self.add_widget(log_title)

        # --- Scrollable Log Area ---
        scroll = ScrollView(size_hint=(1, 0.55))
        self.log_box = BoxLayout(
            orientation='vertical',
            size_hint_y=None,
            spacing=6,
            padding=[10, 10]
        )
        self.log_box.bind(minimum_height=self.log_box.setter('height'))

        with self.log_box.canvas.before:
            Color(0.08, 0.08, 0.18, 1)
            self.log_rect = RoundedRectangle(
                pos=self.log_box.pos,
                size=self.log_box.size,
                radius=[12]
            )
        self.log_box.bind(pos=self._update_rect, size=self._update_rect)

        scroll.add_widget(self.log_box)
        self.add_widget(scroll)

        # Add placeholder log entry
        self._add_log("System ready. Waiting for activation...")

    def _update_rect(self, instance, value):
        self.log_rect.pos = instance.pos
        self.log_rect.size = instance.size

    def _add_log(self, text):
        entry = Label(
            text=f"› {text}",
            font_size=14,
            color=(0.7, 0.9, 1.0, 1),
            size_hint_y=None,
            height=30,
            halign='left',
            text_size=(Window.width - 80, None)
        )
        self.log_box.add_widget(entry)
        self.log_lines.append(entry)

        # Keep only last 8 entries
        if len(self.log_lines) > 8:
            old = self.log_lines.pop(0)
            self.log_box.remove_widget(old)

    def toggle_jarvis(self, instance):
        self.is_active = not self.is_active

        if self.is_active:
            self.status_label.text = "● LISTENING"
            self.status_label.color = (0.0, 1.0, 0.4, 1)
            self.toggle_btn.text = "DEACTIVATE"
            self.toggle_btn.background_color = (0.9, 0.2, 0.2, 1)
            self._add_log("Jarvis activated. Listening...")
        else:
            self.status_label.text = "● IDLE"
            self.status_label.color = (0.6, 0.6, 0.6, 1)
            self.toggle_btn.text = "ACTIVATE"
            self.toggle_btn.background_color = (0.0, 0.6, 0.9, 1)
            self._add_log("Jarvis deactivated.")


class JarvisApp(App):
    def build(self):
        self.title = "Jarvis"
        return JarvisUI()


if __name__ == "__main__":
    JarvisApp().run()
