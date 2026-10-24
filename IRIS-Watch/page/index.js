import * as ui from '@zos/ui';
import { BasePage } from '@zeppos/zml/base-page';

Page(BasePage({
 build() {
  this.alive = true;
  this.busy = false;
  this.requestId = 0;
  this.currentTab = 'main';
  this.batteryPct = '--%';
  this.chargingState = 'Tap to query';
  this.irisState = 'Standby';
  this.lastUpdated = '';
  this.statusMsg = '● Ready · Bridge Connected';
  this.statusColor = 0x34D399;
  this.widgets = [];

  this.renderMain();
 },

 clearWidgets() {
  if (this.widgets && this.widgets.length) {
   this.widgets.forEach(w => {
    try {
     if (ui.deleteWidget) ui.deleteWidget(w);
    } catch (e) {}
   });
  }
  this.widgets = [];
 },

 renderMain() {
  this.clearWidgets();
  this.currentTab = 'main';

  // Authentic glowing IRIS orb logo centered at top of round AMOLED display
  try {
   this.widgets.push(ui.createWidget((ui.widget && ui.widget.IMG) || 1, {
    x: 216,
    y: 16,
    src: 'iris_logo.png'
   }));
  } catch (e) {}

  this.widgets.push(ui.createWidget(ui.widget.TEXT, {
   x: 70,
   y: 64,
   w: 340,
   h: 24,
   text_size: 17,
   color: 0x00F0FF,
   align_h: ui.align.CENTER,
   text: 'IRIS REMOTE'
  }));

  // Compact, non-overflowing status badge contoured to AMOLED circular screen
  this.status = ui.createWidget(ui.widget.TEXT, {
   x: 80,
   y: 92,
   w: 320,
   h: 32,
   text_size: 15,
   color: this.statusColor || 0x34D399,
   align_h: ui.align.CENTER,
   text: this.statusMsg || '● Ready · Bridge Connected'
  });
  this.widgets.push(this.status);

  const buttons = [
   { action: 'open_status', x: 60, y: 136, w: 360, h: 58, radius: 20, normal_color: 0x0c2538, press_color: 0x164e63, color: 0x22D3EE, text_size: 19, text: '📊 Phone Battery & Status' },
   { action: 'find',        x: 45, y: 202, w: 390, h: 62, radius: 22, normal_color: 0x063524, press_color: 0x065f46, color: 0x34D399, text_size: 20, text: '🔔 Ring My Phone' },
   { action: 'stop',        x: 45, y: 272, w: 390, h: 62, radius: 22, normal_color: 0x3b111e, press_color: 0x701a2e, color: 0xFB7185, text_size: 20, text: '🔕 Silence Phone Alarm' },
   { action: 'talk',        x: 60, y: 342, w: 360, h: 58, radius: 20, normal_color: 0x24143d, press_color: 0x4c1d95, color: 0xC084FC, text_size: 19, text: '🎙 Talk to IRIS (Voice)' }
  ];

  buttons.forEach(b => {
   this.widgets.push(ui.createWidget(ui.widget.BUTTON, {
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
    click_func: () => {
     if (b.action === 'open_status') {
      this.openStatusTab();
     } else {
      this.send(b.action);
     }
    }
   }));
  });

  // Contoured footer text centered inside circular AMOLED screen safe area
  this.widgets.push(ui.createWidget(ui.widget.TEXT, {
   x: 100,
   y: 418,
   w: 280,
   h: 22,
   text_size: 13,
   color: 0x64748B,
   align_h: ui.align.CENTER,
   text: '● Encrypted · Port 18473'
  }));
 },

 openStatusTab() {
  this.currentTab = 'status';
  this.renderStatusTab();
  this.send('status');
 },

 renderStatusTab() {
  this.clearWidgets();
  this.currentTab = 'status';

  // Centered logo
  try {
   this.widgets.push(ui.createWidget((ui.widget && ui.widget.IMG) || 1, {
    x: 216,
    y: 12,
    src: 'iris_logo.png'
   }));
  } catch (e) {}

  // Title
  this.widgets.push(ui.createWidget(ui.widget.TEXT, {
   x: 70,
   y: 54,
   w: 340,
   h: 26,
   text_size: 18,
   color: 0x00F0FF,
   align_h: ui.align.CENTER,
   text: 'PHONE STATUS'
  }));

  // Card 1: Giant glanceable battery readout
  const pctNum = parseInt(this.batteryPct, 10);
  const battColor = isNaN(pctNum) ? 0x22D3EE : (pctNum > 50 ? 0x00FF9D : (pctNum > 20 ? 0xFBBF24 : 0xFB7185));
  this.battValWidget = ui.createWidget(ui.widget.TEXT, {
   x: 60,
   y: 88,
   w: 360,
   h: 46,
   text_size: 36,
   color: battColor,
   align_h: ui.align.CENTER,
   text: '⚡ ' + this.batteryPct
  });
  this.widgets.push(this.battValWidget);

  this.chargingWidget = ui.createWidget(ui.widget.TEXT, {
   x: 50,
   y: 138,
   w: 380,
   h: 28,
   text_size: 16,
   color: 0x94A3B8,
   align_h: ui.align.CENTER,
   text: this.chargingState
  });
  this.widgets.push(this.chargingWidget);

  // Card 2: Assistant & Link Details
  this.irisWidget = ui.createWidget(ui.widget.TEXT, {
   x: 50,
   y: 184,
   w: 380,
   h: 28,
   text_size: 17,
   color: this.irisState === 'Active' ? 0x00FF9D : 0x38BDF8,
   align_h: ui.align.CENTER,
   text: '🎙 IRIS Assistant: ' + this.irisState
  });
  this.widgets.push(this.irisWidget);

  this.linkWidget = ui.createWidget(ui.widget.TEXT, {
   x: 50,
   y: 216,
   w: 380,
   h: 24,
   text_size: 14,
   color: 0x64748B,
   align_h: ui.align.CENTER,
   text: '📡 Port 18473 · Loopback Online'
  });
  this.widgets.push(this.linkWidget);

  // Refresh Details Button
  this.widgets.push(ui.createWidget(ui.widget.BUTTON, {
   x: 60,
   y: 266,
   w: 360,
   h: 54,
   radius: 20,
   normal_color: 0x0c2538,
   press_color: 0x164e63,
   color: 0x22D3EE,
   text_size: 18,
   text: '🔄 Refresh Details',
   click_func: () => this.send('status')
  }));

  // Back to Main Controls Button
  this.widgets.push(ui.createWidget(ui.widget.BUTTON, {
   x: 60,
   y: 332,
   w: 360,
   h: 54,
   radius: 20,
   normal_color: 0x1e1e2e,
   press_color: 0x2e2e42,
   color: 0xF1F5F9,
   text_size: 18,
   text: '◀ Back to Controls',
   click_func: () => this.renderMain()
  }));

  // Footer status
  this.widgets.push(ui.createWidget(ui.widget.TEXT, {
   x: 100,
   y: 416,
   w: 280,
   h: 22,
   text_size: 13,
   color: 0x64748B,
   align_h: ui.align.CENTER,
   text: this.lastUpdated || '● Live Wrist Link'
  }));
 },

 setStatus(text, color) {
  if (!this.alive) return;
  this.statusMsg = text;
  if (color !== undefined) this.statusColor = color;
  if (this.currentTab === 'main' && this.status && this.status.setProperty) {
   this.status.setProperty(ui.prop.TEXT, text);
   if (ui.prop.COLOR && color !== undefined) {
    this.status.setProperty(ui.prop.COLOR, color);
   }
  }
 },

 parseStatusData(data) {
  const rawMsg = (data && data.message) ? String(data.message) : (typeof data === 'string' ? data : '');

  // 1. Battery Percentage
  let battery = (data && data.battery !== undefined && data.battery >= 0) ? (data.battery + '%') : null;
  if (!battery) {
   const match = rawMsg.match(/(\d+)\s*percent/i);
   if (match) battery = match[1] + '%';
  }
  this.batteryPct = battery || '--%';

  // 2. Charging State Description
  let charging = (data && data.charging) ? String(data.charging) : null;
  if (!charging || charging.includes('Battery is at')) {
   if (rawMsg.includes('is charging')) {
    charging = 'Charging' + (rawMsg.includes('USB') ? ' over USB' : rawMsg.includes('wirelessly') ? ' wirelessly' : ' (AC)');
   } else if (rawMsg.includes('fully charged')) {
    charging = 'Fully Charged';
   } else if (rawMsg.includes('not charging')) {
    charging = 'Not Charging (Unplugged)';
   } else {
    charging = 'Normal Discharging';
   }
  }
  this.chargingState = charging;

  // 3. IRIS Voice Assistant State
  let iris = (data && data.iris) ? String(data.iris) : null;
  if (!iris) {
   iris = rawMsg.includes('IRIS Active') ? 'Active' : 'Standby';
  }
  this.irisState = iris;
  this.lastUpdated = '● Updated: Just now';
 },

 send(action) {
  if (this.busy) return;
  this.busy = true;

  if (action === 'status') {
   if (this.currentTab === 'status' && this.chargingWidget && this.chargingWidget.setProperty) {
    this.chargingWidget.setProperty(ui.prop.TEXT, 'Refreshing status…');
   } else {
    this.setStatus('📊 Querying status…', 0x22D3EE);
   }
  } else if (action === 'find') {
   this.setStatus('🔔 Sending ring command…', 0xFBBF24);
  } else if (action === 'stop') {
   this.setStatus('🔕 Silencing phone alarm…', 0xFB7185);
  } else if (action === 'talk') {
   this.setStatus('🎙 Requesting voice talk…', 0xC084FC);
  } else {
   this.setStatus('Connecting to phone…', 0x00F0FF);
  }

  const id = ++this.requestId;
  const timeout = setTimeout(() => {
   if (this.alive && id === this.requestId) {
    this.requestId++;
    this.busy = false;
    if (this.currentTab === 'status' && this.chargingWidget && this.chargingWidget.setProperty) {
     this.chargingWidget.setProperty(ui.prop.TEXT, 'Query timed out');
    }
    this.setStatus('Timeout. Make sure IRIS is running.', 0xFB7185);
   }
  }, 8000);

  this.request({ method: 'IRIS', action })
   .then(data => {
    clearTimeout(timeout);
    if (this.alive && id === this.requestId) {
     this.busy = false;
     if (action === 'status') {
      this.parseStatusData(data);
      if (this.currentTab === 'status') {
       this.renderStatusTab();
      } else {
       this.setStatus('● ' + this.batteryPct + ' · ' + this.chargingState, 0x34D399);
      }
     } else {
      const msg = (data && data.message) ? data.message : (typeof data === 'string' ? data : 'Command sent');
      const isErr = msg.includes('unreachable') || msg.includes('offline') || msg.includes('first') || msg.includes('blocked');
      this.setStatus(msg, isErr ? 0xFB7185 : 0x34D399);
     }
    }
   })
   .catch(() => {
    clearTimeout(timeout);
    if (this.alive && id === this.requestId) {
     this.busy = false;
     this.setStatus('Phone bridge unreachable', 0xFB7185);
     if (this.currentTab === 'status' && this.chargingWidget && this.chargingWidget.setProperty) {
      this.chargingWidget.setProperty(ui.prop.TEXT, 'Phone unreachable');
     }
    }
   });
 },

 onDestroy() {
  this.alive = false;
  this.requestId++;
 }
}));
