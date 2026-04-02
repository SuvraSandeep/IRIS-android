# Jarvis - Main entry point (placeholder for now)
from kivy.app import App
from kivy.uix.label import Label

class JarvisApp(App):
    def build(self):
        return Label(text="Jarvis is ready.")

if __name__ == "__main__":
    JarvisApp().run()
