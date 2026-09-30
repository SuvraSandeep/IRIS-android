import * as ui from '@zos/ui';
import { BasePage } from '@zeppos/zml/base-page';
Page(BasePage({
 build() {
  this.alive = true; this.busy = false; this.requestId=0;
  this.status = ui.createWidget(ui.widget.TEXT, {x:65,y:35,w:350,h:70,text_size:22,color:0xffffff,align_h:ui.align.CENTER,text:'IRIS Remote · phone microphone'});
  ['status','find','stop','talk'].forEach((action,i)=>ui.createWidget(ui.widget.BUTTON,{x:90,y:115+i*66,w:300,h:56,radius:18,normal_color:0x23445a,press_color:0x36718f,color:0xffffff,text_size:23,text:['Phone status','Ring phone','Stop ringing','Request talk'][i],click_func:()=>this.send(action)}));
 },
 send(action) {
  if(this.busy)return;this.busy=true;this.status.setProperty(ui.prop.TEXT,'Contacting phone…');
  const id=++this.requestId;const timeout=setTimeout(()=>{if(this.alive&&id===this.requestId){this.requestId++;this.busy=false;this.status.setProperty(ui.prop.TEXT,'No response. Check Zepp and IRIS.');}},8000);
  this.request({method:'IRIS',action}).then(data=>{clearTimeout(timeout);if(this.alive&&id===this.requestId){this.busy=false;this.status.setProperty(ui.prop.TEXT,data.message||'No result');}}).catch(()=>{clearTimeout(timeout);if(this.alive&&id===this.requestId){this.busy=false;this.status.setProperty(ui.prop.TEXT,'Phone disconnected');}});
 },
 onDestroy(){this.alive=false;this.requestId++;}
}));
