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
   y: 14,
   src: 'iris_logo.png'
  });

  this.title = ui.createWidget(ui.widget.TEXT, {
   x: 70,
   y: 64,
   w: 340,
   h: 24,
   text_size: 17,
   color: 0x00F0FF,
   align_h: ui.align.CENTER,
   text: 'IRIS REMOTE'
  });

  this.status = ui.createWidget(ui.widget.TEXT, {
   x: 40,
   y: 90,
   w: 400,
   h: 46,
   text_size: 15,
   color: 0x34D399,
   align_h: ui.align.CENTER,
   text: 'Ready · Tap an action below'
  });

  const buttons = [
   { action: 'status', x: 60, y: 142, w: 360, h: 58, radius: 20, normal_color: 0x0c2538, press_color: 0x164e63, color: 0x22D3EE, text_size: 19, text: '📊 Phone Battery & Status' },
   { action: 'find',   x: 45, y: 208, w: 390, h: 62, radius: 22, normal_color: 0x063524, press_color: 0x065f46, color: 0x34D399, text_size: 20, text: '🔔 Ring My Phone' },
   { action: 'stop',   x: 45, y: 278, w: 390, h: 62, radius: 22, normal_color: 0x3b111e, press_color: 0x701a2e, color: 0xFB7185, text_size: 20, text: '🔕 Silence Phone Alarm' },
   { action: 'talk',   x: 60, y: 348, w: 360, h: 58, radius: 20, normal_color: 0x24143d, press_color: 0x4c1d95, color: 0xC084FC, text_size: 19, text: '🎙 Talk to IRIS (Voice)' }
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

  // Contoured footer text centered inside circular AMOLED screen safe area
  this.footer = ui.createWidget(ui.widget.TEXT, {
   x: 100,
   y: 420,
   w: 280,
   h: 22,
   text_size: 13,
   color: 0x64748B,
   align_h: ui.align.CENTER,
   text: '● Encrypted · Port 18473'
  });
 },

 setStatus(text, color) {
  if (!this.alive) return;
  this.status.setProperty(ui.prop.TEXT, text);
  if (ui.prop.COLOR && color !== undefined) {
   this.status.setProperty(ui.prop.COLOR, color);
  }
 },

 send(action) {
  if (this.busy) return;
  this.busy = true;

  if (action === 'status') this.setStatus('📊 Querying phone status…', 0x22D3EE);
  else if (action === 'find') this.setStatus('🔔 Sending ring command…', 0xFBBF24);
  else if (action === 'stop') this.setStatus('🔕 Silencing phone alarm…', 0xFB7185);
  else if (action === 'talk') this.setStatus('🎙 Requesting talk session…', 0xC084FC);
  else this.setStatus('Connecting to phone…', 0x00F0FF);

  const id = ++this.requestId;
  const timeout = setTimeout(() => {
   if (this.alive && id === this.requestId) {
    this.requestId++;
    this.busy = false;
    this.setStatus('Timeout. Make sure IRIS is running.', 0xFB7185);
   }
  }, 8000);

  this.request({ method: 'IRIS', action })
   .then(data => {
    clearTimeout(timeout);
    if (this.alive && id === this.requestId) {
     this.busy = false;
     const msg = data.message || 'No response';
     const isErr = msg.includes('unreachable') || msg.includes('offline') || msg.includes('first') || msg.includes('blocked');
     this.setStatus(msg, isErr ? 0xFB7185 : 0x34D399);
    }
   })
   .catch(() => {
    clearTimeout(timeout);
    if (this.alive && id === this.requestId) {
     this.busy = false;
     this.setStatus('Phone bridge unreachable', 0xFB7185);
    }
   });
 },

 onDestroy() {
  this.alive = false;
  this.requestId++;
 }
}));
