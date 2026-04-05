import os
import sys
import time
import threading

# ── Stub out unavailable native modules on Android ──
try:
    import vosk
except ImportError:
    vosk = None

try:
    import sounddevice as sd
except ImportError:
    sd = None

from kivy.app import App
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.screenmanager import Screen, ScreenManager
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
        self._listen_thread = None
        self._running = False
        Clock.schedule_interval(self._tick, 1)

    def _tick(self, dt):
        if self.is_active:
            elapsed = int(time.time() - self._start_time)
            m, s = divmod(elapsed, 60)
            try:
                self.ids.uptime_label.text = f'{m:02d}:{s:02d}'
            except Exception:
                pass

    def toggle_system(self, active):
        self.is_active = active
        if active:
            self.status_text  = 'ACTIVE'
            self.toggle_label = 'SYSTEM ON'
            self.subtitle_text = 'listening…' if sd else 'mic unavailable'
            self._start_time  = time.time()
            self._log('IRIS: System activated.')
            if sd and vosk:
                self._start_listening()
            else:
                self._log('IRIS: Voice engine offline (stub mode).')
        else:
            self.status_text  = 'STANDBY'
            self.toggle_label = 'SYSTEM OFF'
            self.subtitle_text = 'tap toggle to activate'
            self._running = False
            self._log('IRIS: System deactivated.')

    def _log(self, text, color='00ffff'):
        try:
            from kivy.uix.label import Label
            lbl = Label(
                text=f'[color=3a3a3a]{time.strftime("%H:%M:%S")}[/color]  '
                     f'[color={color}]{text}[/color]',
                markup=True,
                font_name='RobotoMono',
                font_size='10sp',
                size_hint_y=None,
                halign='left',
            )
            lbl.bind(texture_size=lbl.setter('size'))
            lbl.text_size = (lbl.width, None)
            log_list = self.ids.sm.get_screen('main').ids.log_list
            log_list.add_widget(lbl)
            self.ids.sm.get_screen('main').ids.log_scroll.scroll_to(lbl)
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
        cmd = cmd.lower()
        try:
            from android.permissions import request_permissions, Permission
        except ImportError:
            self._log(f'IRIS: {cmd} → stub (no android)', 'ffaa00')
            return
        if 'youtube' in cmd:
            self._open_url('https://www.youtube.com')
        elif 'call' in cmd:
            self._log('IRIS: Call — contact picker not yet wired.', 'ffaa00')
        elif 'battery' in cmd:
            self._battery_status()
        elif 'map' in cmd:
            self._open_url('https://maps.google.com')
        elif 'alarm' in cmd:
            self._log('IRIS: Alarm — not yet wired.', 'ffaa00')
        else:
            self._log(f'IRIS: Unknown command "{cmd}"', 'ff3b3b')

    def _open_url(self, url):
        try:
            from android import activity
            from jnius import autoclass
            Intent  = autoclass('android.content.Intent')
            Uri     = autoclass('android.net.Uri')
            intent  = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            activity.startActivity(intent)
            self._log(f'IRIS: Opened {url}')
        except Exception as e:
            self._log(f'IRIS: open URL failed — {e}', 'ff3b3b')

    def _battery_status(self):
        try:
            from plyer import battery
            charge = battery.status.get('percentage', '?')
            self._log(f'IRIS: Battery {charge}%')
        except Exception as e:
            self._log(f'IRIS: Battery — {e}', 'ffaa00')

    def _start_listening(self):
        self._running = True
        self._listen_thread = threading.Thread(
            target=self._listen_loop, daemon=True)
        self._listen_thread.start()

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
                            Clock.schedule_once(
                                lambda dt, r=result: self._on_voice(r))
        except Exception as e:
            Clock.schedule_once(
                lambda dt, err=e: self._log(f'IRIS: Voice error — {err}', 'ff3b3b'))

    def _on_voice(self, text):
        self._log(f'HEARD: {text}', 'aaffff')
        self.quick_action(text)

    def try_debug_unlock(self, pw):
        try:
            msg = self.ids.sm.get_screen('debug').ids.debug_msg
            if pw == 'iris_debug':
                msg.text = 'ACCESS GRANTED'
                msg.color = (0, 1, 0.53, 0.9)
            else:
                msg.text = 'ACCESS DENIED'
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
