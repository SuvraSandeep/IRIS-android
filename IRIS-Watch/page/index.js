import * as ui from '@zos/ui';
import { BasePage } from '@zeppos/zml/base-page';

Page(BasePage({
 build() {
  this.alive = true;
  this.busy = false;
  this.requestId = 0;

  // Authentic glowing IRIS orb logo centered at top of round AMOLED display
  this.logo = ui.createWidget(ui.widget.IMG, {
   x: 216,
   y: 16,
   src: 'iris_logo.png'
  });

  this.title = ui.createWidget(ui.widget.TEXT, {
   x: 70,
   y: 68,
   w: 340,
   h: 26,
   text_size: 18,
   color: 0x00F0FF,
   align_h: ui.align.CENTER,
   text: 'IRIS REMOTE'
  });

  this.status = ui.createWidget(ui.widget.TEXT, {
   x: 45,
   y: 96,
   w: 390,
   h: 44,
   text_size: 17,
   color: 0x34D399,
   align_h: ui.align.CENTER,
   text: 'Ready · Tap an action below'
  });

  const buttons = [
   { action: 'status', x: 60, y: 144, w: 360, h: 60, radius: 22, normal_color: 0x0c2538, press_color: 0x164e63, color: 0x22D3EE, text_size: 20, text: '📊 Phone Battery & Status' },
   { action: 'find',   x: 45, y: 212, w: 390, h: 64, radius: 24, normal_color: 0x063524, press_color: 0x065f46, color: 0x34D399, text_size: 21, text: '🔔 Ring My Phone' },
   { action: 'stop',   x: 45, y: 284, w: 390, h: 64, radius: 24, normal_color: 0x3b111e, press_color: 0x701a2e, color: 0xFB7185, text_size: 21, text: '🔕 Silence Phone Alarm' },
   { action: 'talk',   x: 60, y: 356, w: 360, h: 60, radius: 22, normal_color: 0x24143d, press_color: 0x4c1d95, color: 0xC084FC, text_size: 20, text: '🎙 Talk to IRIS (Voice)' }
  ];

  buttons.forEach(b => ui.createWidget(ui.widget.BUTTON, {
   x: b.x,
   y: b.y,
   w: b.w,
   h: b.h,
   radius: b.radius,
   normal_color: b.normal_color,
   press_color: b.press_color,
   color: b.color,
   text_size: b.text_size,
   text: b.text,
   click_func: () => this.send(b.action)
  }));

  this.footer = ui.createWidget(ui.widget.TEXT, {
   x: 90,
   y: 428,
   w: 300,
   h: 26,
   text_size: 14,
   color: 0x64748B,
   align_h: ui.align.CENTER,
   text: 'Encrypted Link · Port 18473'
  });
 },

 send(action) {
  if (this.busy) return;
  this.busy = true;
  this.status.setProperty(ui.prop.TEXT, 'Connecting to phone…');
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
