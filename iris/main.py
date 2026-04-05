import sys
import traceback

from kivy.app import App
from kivy.uix.label import Label
from kivy.uix.scrollview import ScrollView


class IRISApp(App):
    def build(self):
        try:
            from kivy.lang import Builder
            from kivy.uix.boxlayout import BoxLayout
            from kivy.uix.screenmanager import Screen
            from kivy.properties import StringProperty, BooleanProperty, NumericProperty
            from kivy.clock import Clock
            from kivy.core.text import LabelBase

            try:
                LabelBase.register('RobotoMono', fn_regular='RobotoMono-Regular.ttf')
            except Exception:
                pass

            import iris_app
            return iris_app.IRISRoot()

        except Exception:
            tb = traceback.format_exc()
            lbl = Label(
                text=tb,
                font_size='11sp',
                color=(1, 0.3, 0.3, 1),
                text_size=(None, None),
                halign='left',
                valign='top',
                size_hint_y=None)
            lbl.bind(texture_size=lbl.setter('size'))
            sv = ScrollView()
            sv.add_widget(lbl)
            return sv


if __name__ == '__main__':
    IRISApp().run()
