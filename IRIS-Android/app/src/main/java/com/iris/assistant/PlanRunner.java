package com.iris.assistant;

import android.content.Context;
import android.content.Intent;

import java.util.List;

/**
 * Phase 2 & 5 — Central execution engine for structured plans and tool calls.
 *
 * Implements the "Android tool executor" layer (AGENT-ARCHITECTURE.md §3, §7):
 * validates every tool step against known capabilities and executes them
 * through the host service, preventing disparate command execution paths.
 */
public class PlanRunner {

    public interface Host {
        Context getContext();
        void reply(String message);
        void broadcastMessage(String message);
        void speak(String text);
        void speakThenRun(String text, Runnable after);
        void rearmAfterAction();
        void startActivity(Intent intent);
        Object getSystemService(String name);
        void handleTorch(boolean on);
        void handleSetVolumePercent(String value);
        void handleVolume(String normalized);
        void handleMediaControl(String command);
        void handleSetAlarm(String timeText);
        void handleSetTimer(String durationText);
        void handleReminder(String task, String inAt, String timeText);
        void handleWeather(String query);
        void handleLocation();
        void handleQuickAction(String action);
        void handleNotifications(String query);
        void handleTakePhoto(String camWord, boolean flash);
        void handleScreenshot();
        void handlePlaySong(String query);
        void handleWebSearch(String query);
        void handleNavigate(String destination);
        void handleEmail(String recipient, String body);
        void handleCalendar(String title, String when);
        void handleAppIntegration(AppRequest request);
        void handleOpenApp(String name);
        void handlePhoneStatus();
        void handleConnectivity(String which);
        void handleRinger(int mode);
        void handleDnd(boolean on);
        void handleAirplane(boolean turnOn);
        void handleModeSet(String action, String mode, String trailing);
        void handleModeQuery(String mode);
        void handleBatterySaverSet(String action, String onOff, String scope);
        void handleBatterySaverQuery();
        void handleCallContact(String name);
        void handleSendSms(String recipient, String message);
        void handleStartScreenRecording(String duration);
        void handleRecordCameraVideo(String camera, String duration);
        void handleRecordVoice(String duration);
        void handleStopRecording();
    }

    private final Host host;

    public PlanRunner(Host host) {
        this.host = host;
    }

    /**
     * Execute a validated, complete plan.
     * Returns true if execution was dispatched, false otherwise.
     */
    public boolean execute(Plan plan) {
        if (plan == null || plan.isUnknown() || !plan.isComplete() || !plan.allStepsKnown()) {
            return false;
        }

        List<ToolCall> steps = plan.steps();
        if (steps.isEmpty()) return false;

        boolean anySuccess = false;
        for (ToolCall step : steps) {
            if (executeStep(step)) {
                anySuccess = true;
            }
        }
        return anySuccess;
    }

    /**
     * Execute a single validated ToolCall step against the host environment.
     */
    public boolean executeStep(ToolCall call) {
        if (call == null || !call.isKnown() || host == null) return false;
        String tool = call.tool();

        switch (tool) {
            case "call_contact": {
                String name = call.arg("name");
                if (name.isEmpty()) name = call.arg("recipient");
                host.handleCallContact(name);
                return true;
            }
            case "compose_message": {
                String name = call.arg("name");
                if (name.isEmpty()) name = call.arg("recipient");
                String text = call.arg("text");
                if (text.isEmpty()) text = call.arg("message");
                host.handleSendSms(name, text);
                return true;
            }
            case "set_torch": {
                String state = call.arg("state");
                host.handleTorch(!"off".equalsIgnoreCase(state));
                return true;
            }
            case "create_alarm": {
                host.handleSetAlarm(call.arg("time"));
                return true;
            }
            case "create_timer": {
                host.handleSetTimer(call.arg("duration"));
                return true;
            }
            case "create_reminder": {
                host.handleReminder(call.arg("task"), call.arg("when"), call.arg("time"));
                return true;
            }
            case "set_volume": {
                host.handleSetVolumePercent(call.arg("level"));
                return true;
            }
            case "take_photo": {
                host.handleTakePhoto(call.arg("camera"), "true".equalsIgnoreCase(call.arg("flash")));
                return true;
            }
            case "take_screenshot": {
                host.handleScreenshot();
                return true;
            }
            case "record_screen": {
                host.handleStartScreenRecording(call.arg("duration"));
                return true;
            }
            case "record_camera_video": {
                host.handleRecordCameraVideo(call.arg("camera"), call.arg("duration"));
                return true;
            }
            case "record_voice": {
                host.handleRecordVoice(call.arg("duration"));
                return true;
            }
            case "stop_recording": {
                host.handleStopRecording();
                return true;
            }
            case "open_app": {
                String app = call.arg("app");
                if (app.isEmpty()) app = call.arg("name");
                host.handleOpenApp(app);
                return true;
            }
            case "search_web": {
                host.handleWebSearch(call.arg("query"));
                return true;
            }
            case "search_app": {
                AppRequest appReq = new AppRequest("search", call.arg("app"), call.arg("value"));
                host.handleAppIntegration(appReq);
                return true;
            }
            case "share_text_to_app": {
                AppRequest appReq = new AppRequest("share_text", call.arg("app"), call.arg("value"));
                host.handleAppIntegration(appReq);
                return true;
            }
            case "share_recent_media": {
                AppRequest appReq = new AppRequest("share_media", call.arg("app"), call.arg("value"));
                host.handleAppIntegration(appReq);
                return true;
            }
            case "phone_status": {
                host.handlePhoneStatus();
                return true;
            }
            case "read_notifications": {
                host.handleNotifications(call.arg("query"));
                return true;
            }
            default:
                return false;
        }
    }
}
