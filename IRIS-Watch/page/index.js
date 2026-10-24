import * as ui from '@zos/ui';
import { BasePage } from '@zeppos/zml/base-page';

Page(BasePage({
 build() {
  this.alive = true;
  this.busy = false;
  this.requestId = 0;

  this.header = ui.createWidget(ui.widget.TEXT, {
   x: 70, y: 22, w: 340, h: 28,
   text_size: 18, color: 0x00F0FF, align_h: ui.align.CENTER,
   text: 'IRIS // T-REX 3 HUD'
  });

  this.status = ui.createWidget(ui.widget.TEXT, {
   x: 45, y: 52, w: 390, h: 62,
   text_size: 20, color: 0x34D399, align_h: ui.align.CENTER,
   text: '[ STANDBY // READY ]\nTAP COMMAND BELOW'
  });

  const buttons = [
   { action: 'status', x: 65, y: 120, w: 350, h: 62, radius: 22, normal_color: 0x0c2538, press_color: 0x164e63, color: 0x00F0FF, text_size: 21, text: '⬡ TELEMETRY STATUS' },
   { action: 'find',   x: 50, y: 190, w: 380, h: 66, radius: 24, normal_color: 0x063524, press_color: 0x065f46, color: 0x00FF9D, text_size: 22, text: '◈ FIND PHONE (RING)' },
   { action: 'stop',   x: 50, y: 264, w: 380, h: 66, radius: 24, normal_color: 0x3b111e, press_color: 0x701a2e, color: 0xFF0055, text_size: 22, text: '✕ SILENCE ALARM' },
   { action: 'talk',   x: 65, y: 338, w: 350, h: 62, radius: 22, normal_color: 0x24143d, press_color: 0x4c1d95, color: 0xC084FC, text_size: 21, text: '▲ VOICE COMM TALK' }
  ];

  buttons.forEach(b => ui.createWidget(ui.widget.BUTTON, {
   x: b.x, y: b.y, w: b.w, h: b.h, radius: b.radius,
   normal_color: b.normal_color, press_color: b.press_color,
   color: b.color, text_size: b.text_size, text: b.text,
   click_func: () => this.send(b.action)
  }));

  this.footer = ui.createWidget(ui.widget.TEXT, {
   x: 90, y: 414, w: 300, h: 28,
   text_size: 15, color: 0x64748B, align_h: ui.align.CENTER,
   text: 'SECURE LINK · PORT 18473'
  });
 },

 send(action) {
  if (this.busy) return;
  this.busy = true;
  this.status.setProperty(ui.prop.TEXT, 'Contacting phone…');
  const id = ++this.requestId;
  const timeout = setTimeout(() => {
   if (this.alive && id === this.requestId) {
    this.requestId++;
    this.busy = false;
    this.status.setProperty(ui.prop.TEXT, 'No response. Check Zepp and IRIS.');
   }
  }, 8000);

  this.request({ method: 'IRIS', action })
   .then(data => {
    clearTimeout(timeout);
    if (this.alive && id === this.requestId) {
     this.busy = false;
     this.status.setProperty(ui.prop.TEXT, data.message || 'No result');
    }
   })
   .catch(() => {
    clearTimeout(timeout);
    if (this.alive && id === this.requestId) {
     this.busy = false;
     this.status.setProperty(ui.prop.TEXT, 'Phone disconnected');
    }
   });
 },

 onDestroy() {
  this.alive = false;
  this.requestId++;
 }
}));
