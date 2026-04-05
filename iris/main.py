import sys
import traceback

# Write crash log to app storage
def crash_handler(exc_type, exc_value, exc_tb):
    try:
        from android.storage import app_storage_path
        path = app_storage_path()
    except Exception:
        path = '/sdcard'
    with open(f'{path}/iris_crash.txt', 'w') as f:
        traceback.print_exception(exc_type, exc_value, exc_tb, file=f)
    sys.__excepthook__(exc_type, exc_value, exc_tb)

sys.excepthook = crash_handler

import time

try:
    from kivy.app import App
    from kivy.uix.boxlayout import BoxLayout
    from kivy.uix.screenmanager import Screen
    from kivy.properties import StringProperty, BooleanProperty, NumericProperty
    from kivy.clock import Clock
    from kivy.core.text import LabelBase
except Exception as e:
    crash_handler(*sys.exc_info())
    raise

try:
    LabelBase.register('RobotoMono', fn_regular='RobotoMono-Regular.ttf')
except Exception:
    pass


class IRISRoot(BoxLayout):
    status_text   = StringProperty('STANDBY')
    toggle_label  = StringProperty('SYSTEM OFF')
    subtitle_text = StringProperty('tap toggle to activate')
    is_active     = BooleanProperty(False)
    cmd_count     = NumericProperty(0)

    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self._start_time = time.time()
        self._running = False
        Clock.schedule_interval(self._tick, 1)

    def _tick(self, dt):
        if not self.is_active:
            return
        elapsed = int(time.time() - self._start_time)
        m, s = divmod(elapsed, 60)
        try:
            self.ids.sm.get_screen('main').ids.uptime_label.text = f'{m:02d}:{s:02d}'
        except Exception:
            pass

    def toggle_system(self, active):
        self.is_active = active
        if active:
            self.status_text   = 'ACTIVE'
            self.toggle_label  = 'SYSTEM ON'
            self.subtitle_text = 'ui mode only'
            self._start_time   = time.time()
            self._log('IRIS: System activated.')
        else:
            self.status_text   = 'STANDBY'
            self.toggle_label  = 'SYSTEM OFF'
            self.subtitle_text = 'tap toggle to activate'
            self._running      = False
            self._log('IRIS: System deactivated.')

    def _log(self, text, color='00ffff'):
        try:
            from kivy.uix.label import Label
            ts = time.strftime('%H:%M:%S')
            lbl = Label(
                text=f'[color=3a3a3a]{ts}[/color]  [color={color}]{text}[/color]',
                markup=True, font_size='10sp',
                size_hint_y=None, halign='left')
            lbl.bind(width=lambda i, v: setattr(i, 'text_size', (v, None)))
            lbl.bind(texture_size=lambda i, v: setattr(i, 'height', v[1]))
            screen = self.ids.sm.get_screen('main')
            screen.ids.log_list.add_widget(lbl)
            Clock.schedule_once(
                lambda dt: screen.ids.log_scroll.scroll_to(lbl), 0.1)
        except Exception:
            pass

    def quick_action(self, cmd):
        self.cmd_count += 1
        try:
            self.ids.sm.get_screen('main').ids.cmd_count_label.text = str(self.cmd_count)
        except Exception:
            pass
        self._log(f'CMD: {cmd}')

    def try_debug_unlock(self, pw):
        try:
            msg = self.ids.sm.get_screen('debug').ids.debug_msg
            msg.text = 'ACCESS GRANTED' if pw == 'iris_debug' else 'ACCESS DENIED'
        except Exception:
            pass


class MainScreen(Screen):
    pass

class AnalyticsScreen(Screen):
    pass

class DebugScreen(Screen):
    pass


class IRISApp(App):
    def build(self):
        return IRISRoot()


if __name__ == '__main__':
    IRISApp().run()
