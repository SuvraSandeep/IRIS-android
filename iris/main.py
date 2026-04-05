import sys, os, traceback, time

# Write crash log to phone storage - readable by any file manager
def write_log(msg):
    for path in ['/sdcard/iris_crash.txt', '/storage/emulated/0/iris_crash.txt']:
        try:
            with open(path, 'a') as f:
                f.write(msg + '\n')
            break
        except:
            continue

write_log('--- IRIS START ' + time.strftime('%H:%M:%S') + ' ---')

try:
    write_log('1: kivy import')
    from kivy.core.text import LabelBase
    write_log('2: font register')
    LabelBase.register('RobotoMono', fn_regular='RobotoMono-Regular.ttf')
    write_log('3: other imports')
    from kivy.app import App
    from kivy.uix.boxlayout import BoxLayout
    from kivy.uix.screenmanager import Screen
    from kivy.properties import StringProperty, BooleanProperty, NumericProperty
    from kivy.clock import Clock
    write_log('4: all imports OK')

    try:
        import vosk
        write_log('5: vosk OK')
    except ImportError:
        vosk = None
        write_log('5: vosk not present (OK)')

    try:
        import sounddevice as sd
        write_log('6: sounddevice OK')
    except ImportError:
        sd = None
        write_log('6: sounddevice not present (OK)')

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
            if not self.is_active: return
            elapsed = int(time.time() - self._start_time)
            m, s = divmod(elapsed, 60)
            try: self.ids.sm.get_screen('main').ids.uptime_label.text = f'{m:02d}:{s:02d}'
            except: pass
        def toggle_system(self, active):
            self.is_active = active
            self.status_text = 'ACTIVE' if active else 'STANDBY'
            self.toggle_label = 'SYSTEM ON' if active else 'SYSTEM OFF'
            self.subtitle_text = 'ui mode only' if active else 'tap toggle to activate'
            if active: self._start_time = time.time()
            self._log('IRIS: ' + ('activated.' if active else 'deactivated.'))
        def _log(self, text, color='00ffff'):
            write_log('LOG: ' + text)
            try:
                from kivy.uix.label import Label
                ts = time.strftime('%H:%M:%S')
                lbl = Label(
                    text=f'[color=3a3a3a]{ts}[/color]  [color={color}]{text}[/color]',
                    markup=True, font_size='10sp', size_hint_y=None, halign='left')
                lbl.bind(width=lambda i,v: setattr(i,'text_size',(v,None)))
                lbl.bind(texture_size=lambda i,v: setattr(i,'height',v[1]))
                screen = self.ids.sm.get_screen('main')
                screen.ids.log_list.add_widget(lbl)
                Clock.schedule_once(lambda dt: screen.ids.log_scroll.scroll_to(lbl), 0.1)
            except: pass
        def quick_action(self, cmd):
            self.cmd_count += 1
            self._log(f'CMD: {cmd}')
        def try_debug_unlock(self, pw):
            try:
                msg = self.ids.sm.get_screen('debug').ids.debug_msg
                msg.text = 'ACCESS GRANTED' if pw == 'iris_debug' else 'ACCESS DENIED'
            except: pass

    class MainScreen(Screen): pass
    class AnalyticsScreen(Screen): pass
    class DebugScreen(Screen): pass

    class IRISApp(App):
        def build(self):
            write_log('7: build() called')
            try:
                root = IRISRoot()
                write_log('8: IRISRoot() OK')
                return root
            except Exception:
                write_log('CRASH in build():\n' + traceback.format_exc())
                raise

    write_log('9: calling IRISApp().run()')
    IRISApp().run()

except Exception:
    write_log('FATAL CRASH:\n' + traceback.format_exc())
