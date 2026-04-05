import sys
import traceback

def main():
    from kivy.app import App
    from kivy.uix.label import Label
    from kivy.uix.scrollview import ScrollView

    class IRISApp(App):
        def build(self):
            try:
                from kivy.uix.boxlayout import BoxLayout
                from kivy.uix.screenmanager import Screen, ScreenManager
                from kivy.properties import StringProperty, BooleanProperty, NumericProperty
                
                # Test every import IRIS needs
                errors = []
                
                for mod in ['kivy', 'plyer']:
                    try:
                        __import__(mod)
                    except Exception as e:
                        errors.append(f'FAIL {mod}: {e}')

                # Try loading the KV file manually
                try:
                    from kivy.lang import Builder
                    Builder.load_file('iris.kv')
                    errors.append('OK: iris.kv loaded')
                except Exception as e:
                    errors.append(f'FAIL iris.kv: {traceback.format_exc()[-300:]}')

                sv = ScrollView()
                lbl = Label(
                    text='\n'.join(errors) if errors else 'ALL OK',
                    font_size='13sp',
                    size_hint_y=None,
                    halign='left',
                    valign='top',
                    text_size=(None, None),
                    padding=(20, 20),
                )
                lbl.bind(texture_size=lbl.setter('size'))
                sv.add_widget(lbl)
                return sv
            except Exception as e:
                return Label(text=traceback.format_exc()[-500:], font_size='11sp')

    IRISApp().run()

try:
    main()
except Exception as e:
    pass
