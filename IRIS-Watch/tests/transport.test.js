import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { readFileSync } from 'node:fs';
const source=path=>readFileSync(new URL(path,import.meta.url),'utf8').replace(/^import .*;\n/gm,'');
test('expired watch response cannot overwrite a newer request',async()=>{
 let page,display='',timeouts=[],resolve=[];
 const context={Page:p=>page=p,BasePage:p=>p,ui:{widget:{TEXT:1,BUTTON:2},align:{CENTER:1},prop:{TEXT:1},createWidget:()=>({setProperty:(_,text)=>{display=text;}})},setTimeout:fn=>{timeouts.push(fn);return timeouts.length;},clearTimeout:()=>{}};
 vm.runInNewContext(source('../page/index.js'),context);page.request=()=>new Promise(r=>resolve.push(r));page.build();page.send('status');timeouts[0]();page.send('find');resolve[0]({message:'OLD'});await Promise.resolve();assert.notEqual(display,'OLD');assert.equal(page.busy,true);resolve[1]({message:'Ringing'});await Promise.resolve();assert.equal(display,'Ringing');assert.equal(page.busy,false);
});
test('phone relay rejects unsupported commands and includes expiry metadata',async()=>{
 let side,requests=[],reply;
 const context={AppSideService:p=>side=p,BaseSideService:p=>p,settingsStorage:{getItem:()=> 'a'.repeat(64)},fetch:async r=>{requests.push(r);return {body:{message:'OK'}};},Date};
 vm.runInNewContext(source('../app-side/index.js'),context);side.onRequest({method:'IRIS',action:'send_sms'},(_,r)=>reply=r);assert.equal(requests.length,0);assert.equal(reply.message,'Unsupported request');side.onRequest({method:'IRIS',action:'find'},(_,r)=>reply=r);await Promise.resolve();await Promise.resolve();assert.equal(requests[0].url,'http://127.0.0.1:18473/find');assert.equal(requests[0].method,'POST');assert.equal(requests[0].headers.Authorization,'Bearer '+'a'.repeat(64));assert.ok(Number(requests[0].headers['X-IRIS-Sent-At'])>0);
});
