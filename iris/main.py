import os, time, json, random, threading
os.environ['KIVY_NO_ENV_CONFIG'] = '1'

from kivy.app import App
from kivy.uix.screenmanager import ScreenManager, Screen
from kivy.uix.boxlayout import BoxLayout
from kivy.properties import StringProperty, BooleanProperty
from kivy.clock import Clock, mainthread
from kivy.utils import platform

# ── VOSK ─────────────────────────────────────────────
from vosk import Model, KaldiRecognizer
import queue

SAMPLE_RATE = 16000
MODEL_PATH  = 'models/en-small'
WAKE_WORD   = 'hello iris'

# ── PERSONALITY ──────────────────────────────────────
L_LINES = [
    "Listening. Try not to ramble.", "Go ahead.", "I'm listening.",
    "Input detected. Proceed.", "Receiving.", "Channel open.",
    "Speak. I'm not patient.", "Waiting for coherent input.",
    "Microphone active.", "Recording. Make it count.",
    "Ready. No pressure, but hurry.", "Input accepted.",
    "I'm listening. Try not to confuse me.", "Channel clear.",
    "Acoustic buffer ready.", "Recording session started.",
    "Voice input active.", "Waiting.", "Standing by for input.",
    "Capture initiated."
]
S_LINES = [
    "Executing.", "Handled.", "Done.", "Confirmed.",
    "Processing complete.", "Action dispatched.", "Affirmative.",
    "Completed without incident.", "Noted and executed.",
    "Request fulfilled.", "Dispatched.", "Processed.",
    "On it.", "Executed.", "Filed and actioned."
]
E_TIERS = [
    ["That made no sense.",      "Still unclear.",       "We're not improving.",        "Impressive consistency in failure."],
    ["Command unrecognized.",    "Try again, differently.", "Still failing, I see.",     "Remarkable persistence."],
    ["Input invalid.",           "Unresolved.",          "Third time. Concerning.",      "I'm beginning to lose respect."],
    ["Parse error.",             "Unclear. Again.",      "Pattern: confusion.",          "You're consistent, at least."],
]

def rand(a): return a[random.randint(0, len(a)-1)]

def err_line(fail_count):
    tier = min(fail_count, len(E_TIERS)-1)
    pool = E_TIERS[tier]
    idx  = min(fail_count // 4, len(pool)-1)
    return pool[idx]

# ── INTENT PARSER ────────────────────────────────────
INTENTS = {
    'open youtube':   ('APP_LAUNCH',    'YouTube'),
    'call':           ('DIALER_INTENT', 'Contact'),
    'battery':        ('SYSTEM_QUERY',  'Battery'),
    'messages':       ('NOTIFY_READ',   'WhatsApp'),
    'whatsapp':       ('NOTIFY_READ',   'WhatsApp'),
    'open maps':      ('APP_LAUNCH',    'Maps'),
    'maps':           ('APP_LAUNCH',    'Maps'),
    'alarm':          ('SYSTEM_OP',     'Clock'),
    'set alarm':      ('SYSTEM_OP',     'Clock'),
    'open chrome':    ('APP_LAUNCH',    'Chrome'),
    'open settings':  ('APP_LAUNCH',    'Settings'),
    'take screenshot':('SYSTEM_OP',     'Screenshot'),
    'volume up':      ('SYSTEM_OP',     'Volume+'),
    'volume down':    ('SYSTEM_OP',     'Volume-'),
}

def parse_intent(text):
    text = text.lower().strip()
    for kw, (action, target) in INTENTS.items():
        if kw in text:
            return action, target
    return None, None

# ── SCREENS ──────────────────────────────────────────
class MainScreen(Screen):    pass
class AnalyticsScreen(Screen): pass
class DebugScreen(Screen):   pass

# ── ROOT ─────────────────────────────────────────────
class IRISRoot(BoxLayout):
    status_text   = StringProperty("STANDBY")
    subtitle_text = StringProperty("System standing by.")
    toggle_label  = StringProperty("INITIALIZE")
    is_active     = BooleanProperty(False)

    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.cmd_count    = 0
        self.fail_count   = 0
        self.success_count= 0
        self.latencies    = []
        self.cmd_freq     = {}
        self.start_time   = time.time()
        self.debug_attempts = 3
        self.debug_locked   = False
        self.first_cmd_done = False

        # Voice state
        self._stt_thread  = None
        self._stt_active  = False
        self._audio_q     = queue.Queue()

        # Load Vosk model (background so UI doesn't block)
        self._model = None
        threading.Thread(target=self._load_model, daemon=True).start()

        Clock.schedule_interval(self._update_uptime, 1)

    # ── MODEL LOAD ───────────────────────────────────
    def _load_model(self):
        self._model = Model(MODEL_PATH)
        self._update_subtitle("Model loaded. Ready.")

    @mainthread
    def _update_subtitle(self, text):
        self.subtitle_text = text

    # ── TOGGLE ───────────────────────────────────────
    def toggle_system(self, active):
        self.is_active = active
        if active:
            self.status_text  = "ACTIVE"
            self.toggle_label = "STANDBY"
            self._update_subtitle("System online. Awaiting wake word.")
            self._add_log(None, "System initialized.", None, None, 200)
            self._start_stt()
        else:
            self.status_text  = "STANDBY"
            self.toggle_label = "INITIALIZE"
            self._update_subtitle("System standing by.")
            self._add_log(None, "System deactivated.", None, None, 200)
            self._stop_stt()

    # ── STT THREAD ───────────────────────────────────
    def _start_stt(self):
        if self._stt_active:
            return
        self._stt_active = True
        self._stt_thread = threading.Thread(target=self._stt_loop, daemon=True)
        self._stt_thread.start()

    def _stop_stt(self):
        self._stt_active = False

    def _stt_loop(self):
        """
        Continuous STT loop using Vosk.
        On Android this will use the device microphone via sounddevice.
        In Codespaces (no mic) this loop waits silently — no crash.
        """
        try:
            import sounddevice as sd
            rec = KaldiRecognizer(self._model, SAMPLE_RATE)

            def callback(indata, frames, time_info, status):
                self._audio_q.put(bytes(indata))

            with sd.RawInputStream(samplerate=SAMPLE_RATE, blocksize=4000,
                                   dtype='int16', channels=1,
                                   callback=callback):
                while self._stt_active:
                    data = self._audio_q.get()
                    if rec.AcceptWaveform(data):
                        result = json.loads(rec.Result())
                        text   = result.get('text', '').strip()
                        if text:
                            self._on_transcript(text)
                    else:
                        partial = json.loads(rec.PartialResult())
                        p_text  = partial.get('partial', '').strip()
                        if p_text:
                            Clock.schedule_once(
                                lambda dt, t=p_text: setattr(self, 'subtitle_text', t), 0)
        except Exception as e:
            # No mic available (Codespaces) — silent fail, safe on Android
            pass

    def _on_transcript(self, text):
        text = text.lower().strip()
        if WAKE_WORD in text:
            Clock.schedule_once(lambda dt: self._handle_wake(), 0)
            # Strip wake word, process remainder if any
            cmd = text.replace(WAKE_WORD, '').strip()
            if cmd:
                Clock.schedule_once(lambda dt, c=cmd: self._process_command(c), 0.8)
        elif self.status_text == 'LISTENING':
            Clock.schedule_once(lambda dt, t=text: self._process_command(t), 0)

    @mainthread
    def _handle_wake(self):
        self.status_text  = "WAKE"
        self.subtitle_text = "Wake word detected."

    # ── COMMAND PROCESSING ───────────────────────────
    @mainthread
    def _process_command(self, text):
        self.status_text  = "LISTENING"
        self.subtitle_text = rand(L_LINES)

        Clock.schedule_once(lambda dt: self._run_intent(text), 0.7)

    def _run_intent(self, text):
        self.status_text  = "PROCESSING"
        self.subtitle_text = "Processing..."
        lat = random.randint(160, 420)
        self.latencies.append(lat)

        Clock.schedule_once(lambda dt: self._dispatch(text, lat), lat / 1000)

    def _dispatch(self, text, lat):
        action, target = parse_intent(text)
        self.cmd_count += 1
        self.cmd_freq[text] = self.cmd_freq.get(text, 0) + 1

        try:
            self.ids.cmd_count_label.text = str(self.cmd_count)
            self.ids.latency_label.text   = f"{lat}ms"
        except Exception:
            pass

        if action:
            self.success_count += 1
            self.fail_count     = 0
            resp = rand(S_LINES)
            ctx  = f"{action} → {target}"
            self.status_text   = "RESPONDING"
            self.subtitle_text = resp
            self._add_log(text, resp, ctx, lat, 200)
            self._execute_action(action, target)
        else:
            self.fail_count += 1
            resp = err_line(self.fail_count)
            self.status_text   = "ERROR"
            self.subtitle_text = resp
            self._add_log(text, resp, "ERR: unresolved", lat, 500)

        Clock.schedule_once(lambda dt: self._return_active(), 2.2)

    def _return_active(self):
        if self.is_active:
            self.status_text   = "ACTIVE"
            self.subtitle_text = "Awaiting wake word."

    # ── ACTION EXECUTOR ──────────────────────────────
    def _execute_action(self, action, target):
        if platform != 'android':
            return  # Desktop/Codespaces — skip Android API calls

        try:
            from jnius import autoclass
            Intent   = autoclass('android.content.Intent')
            Uri      = autoclass('android.net.Uri')
            PythonActivity = autoclass('org.kivy.android.PythonActivity')
            context  = PythonActivity.mActivity

            if action == 'APP_LAUNCH':
                pm  = context.getPackageManager()
                pkg_map = {
                    'YouTube': 'com.google.android.youtube',
                    'Maps':    'com.google.android.apps.maps',
                    'Chrome':  'com.android.chrome',
                    'Settings':'com.android.settings',
                }
                pkg = pkg_map.get(target)
                if pkg:
                    launch = pm.getLaunchIntentForPackage(pkg)
                    if launch:
                        context.startActivity(launch)

            elif action == 'DIALER_INTENT':
                i = Intent(Intent.ACTION_DIAL)
                context.startActivity(i)

            elif action == 'SYSTEM_QUERY' and target == 'Battery':
                # Handled by plyer
                from plyer import battery
                status = battery.status
                pct    = status.get('percentage', '?')
                self.subtitle_text = f"Battery: {pct}%"

        except Exception as e:
            self.subtitle_text = f"Action error: {str(e)[:40]}"

    # ── QUICK ACTIONS ────────────────────────────────
    def quick_action(self, cmd):
        if not self.is_active:
            self.subtitle_text = "Initialize system first."
            return
        self._process_command(cmd)

    # ── LOG ──────────────────────────────────────────
    @mainthread
    def _add_log(self, user, iris, action, latency, code):
        try:
            from kivy.uix.label import Label
            t   = time.strftime('%H:%M:%S')
            lat = f"{latency}ms" if latency else "—"
            txt = f"[color=3a3a3a][{t} | {lat} | {code}][/color]\n"
            if user:
                txt += f"[color=e0e0e0]User: {user}[/color]\n"
            color = "00ffff" if code == 200 else "ff3b3b"
            txt += f"[color={color}]IRIS: {iris}[/color]"
            if action:
                txt += f"\n[color=777777]→ {action}[/color]"

            lbl = Label(
                text=txt, markup=True,
                font_name='RobotoMono', font_size='10sp',
                size_hint_y=None, halign='left',
                text_size=(self.ids.log_list.width, None)
            )
            lbl.bind(texture_size=lambda l, v: setattr(l, 'height', v[1]))
            self.ids.log_list.add_widget(lbl)
            Clock.schedule_once(
                lambda dt: setattr(self.ids.log_scroll, 'scroll_y', 0), 0.1)
        except Exception:
            pass

    # ── UPTIME ───────────────────────────────────────
    def _update_uptime(self, dt):
        elapsed = int(time.time() - self.start_time)
        m, s = divmod(elapsed, 60)
        try:
            self.ids.uptime_label.text = f"{m:02d}:{s:02d}"
        except Exception:
            pass

    # ── DEBUG UNLOCK ─────────────────────────────────
    def try_debug_unlock(self, pw):
        PASSWORDS = ['STG', 'iris', 'debug', 'IRIS']
        if pw.strip() in PASSWORDS:
            try:
                self.ids.debug_msg.text  = '[color=00ff88]Access granted.[/color]'
                self.ids.debug_msg.markup = True
            except Exception:
                pass
        else:
            self.debug_attempts -= 1
            msg = f"Invalid. {self.debug_attempts} attempt(s) remaining."
            if self.debug_attempts <= 0:
                msg = "Locked. Restart app to retry."
                self.debug_locked = True
            try:
                self.ids.debug_msg.text = msg
            except Exception:
                pass

# ── APP ───────────────────────────────────────────────
class IRISApp(App):
    def build(self):
        self.title = 'IRIS'
        return IRISRoot()

if __name__ == '__main__':
    IRISApp().run()
# IRIS v0.2
