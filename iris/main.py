import os
import time
import threading

try:
    import vosk
except ImportError:
    vosk = None

try:
    import sounddevice as sd
except ImportError:
    sd = None

from kivy.core.text import LabelBase
LabelBase.register('RobotoMono', fn_regular='RobotoMono-Regular.ttf')

from kivy.app import App
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.screenmanager import Screen
from kivy.properties import StringProperty, BooleanProperty, NumericProperty
from kivy.clock import Clock


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
            self.subtitle_text = 'listening...' if (sd and vosk) else 'ui mode only'
            self._start_time   = time.time()
            self._log('IRIS: System activated.')
            if sd and vosk:
                self._start_listening()
            else:
                self._log('IRIS: Voice offline - stub mode.')
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
                markup=True,
                font_size='10sp',
                size_hint_y=None,
                halign='left',
            )
            lbl.bind(width=lambda i, v: setattr(i, 'text_size', (v, None)))
            lbl.bind(texture_size=lambda i, v: setattr(i, 'height', v[1]))
            screen = self.ids.sm.get_screen('main')
            screen.ids.log_list.add_widget(lbl)
            Clock.schedule_once(lambda dt: screen.ids.log_scroll.scroll_to(lbl), 0.1)
        except Exception:
            pass

    def quick_action(self, cmd):
        self.cmd_count += 1
        try:
            self.ids.sm.get_screen('main').ids.cmd_count_label.text = str(self.cmd_count)
        except Exception:
            pass
        self._log(f'CMD: {cmd}')
        self._dispatch(cmd)

    def _dispatch(self, cmd):
        cmd = cmd.lower().strip()
        try:
            from android import activity
        except ImportError:
            self._log(f'stub: {cmd}', 'ffaa00')
            return
        if 'youtube' in cmd:
            self._open_url('https://www.youtube.com')
        elif 'call' in cmd:
            self._log('IRIS: Call - not wired yet.', 'ffaa00')
        elif 'battery' in cmd:
            self._battery_status()
        elif 'map' in cmd:
            self._open_url('https://maps.google.com')
        elif 'alarm' in cmd:
            self._log('IRIS: Alarm - not wired yet.', 'ffaa00')
        else:
            self._log(f'IRIS: Unknown - "{cmd}"', 'ff3b3b')

    def _open_url(self, url):
        try:
            from android import activity
            from jnius import autoclass
            Intent = autoclass('android.content.Intent')
            Uri    = autoclass('android.net.Uri')
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            self._log(f'IRIS: Opened {url}')
        except Exception as e:
            self._log(f'IRIS: URL error - {e}', 'ff3b3b')

    def _battery_status(self):
        try:
            from plyer import battery
            pct = battery.status.get('percentage', '?')
            self._log(f'IRIS: Battery {pct}%')
        except Exception as e:
            self._log(f'IRIS: Battery - {e}', 'ffaa00')

    def _start_listening(self):
        self._running = True
        threading.Thread(target=self._listen_loop, daemon=True).start()

    def _listen_loop(self):
        try:
            model = vosk.Model('models/en-small')
            rec   = vosk.KaldiRecognizer(model, 16000)
            with sd.RawInputStream(samplerate=16000, blocksize=8000,
                                   dtype='int16', channels=1) as stream:
                while self._running:
                    data, _ = stream.read(4000)
                    if rec.AcceptWaveform(bytes(data)):
                        import json
                        result = json.loads(rec.Result()).get('text', '')
                        if result.strip():
                            Clock.schedule_once(lambda dt, r=result: self._on_voice(r))
        except Exception as e:
            Clock.schedule_once(lambda dt, err=e: self._log(f'Voice error: {err}', 'ff3b3b'))

    def _on_voice(self, text):
        self._log(f'HEARD: {text}', 'aaffff')
        self.quick_action(text)

    def try_debug_unlock(self, pw):
        try:
            msg = self.ids.sm.get_screen('debug').ids.debug_msg
            if pw == 'iris_debug':
                msg.text  = 'ACCESS GRANTED'
                msg.color = (0, 1, 0.53, 0.9)
            else:
                msg.text  = 'ACCESS DENIED'
                msg.color = (1, 0.231, 0.231, 0.7)
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
