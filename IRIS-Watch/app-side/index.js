import { BaseSideService } from '@zeppos/zml/base-side';
import { settingsLib as settingsStorage } from '@zeppos/zml/base-side';
AppSideService(BaseSideService({
 onRequest(req,res){
  if(req.method!=='IRIS'||!['status','find','stop','talk','ping'].includes(req.action)){res(null,{message:'Unsupported request'});return;}
  const raw=(settingsStorage.getItem('irisToken')||'').trim();
  const token=raw.replace(/^Bearer\s+/i,'').replace(/["']/g,'').trim().toLowerCase();
  if(!/^[a-f0-9]{64}$/.test(token)){res(null,{message:'Pair in Zepp mini-app settings first'});return;}
  fetch({url:'http://127.0.0.1:18473/'+req.action,method:(req.action==='status'||req.action==='ping')?'GET':'POST',headers:{Authorization:'Bearer '+token,'X-IRIS-Sent-At':String(Date.now())}})
   .then(response=>{
    if(response.status&&response.status>=400){throw new Error('HTTP '+response.status);}
    const body=typeof response.body==='string'?JSON.parse(response.body):response.body;
    res(null,{message:body?.message||'Phone returned no message'});
   })
   .catch(()=>{
    if(typeof WebSocket!=='undefined'){
     try{
      const ws=new WebSocket('ws://127.0.0.1:18473/ws');
      let done=false;
      const t=setTimeout(()=>{if(!done){done=true;try{ws.close();}catch(e){}res(null,{message:'Bridge unavailable. Start IRIS; Zepp may block local HTTP.'});}},2500);
      ws.onopen=()=>{ws.send(JSON.stringify({action:req.action,token:token,sentAt:Date.now()}));};
      ws.onmessage=(ev)=>{
       if(done)return;done=true;clearTimeout(t);
       try{const data=typeof ev.data==='string'?JSON.parse(ev.data):ev.data;res(null,{message:data?.message||'Phone returned no message'});}
       catch(e){res(null,{message:String(ev.data)||'Phone returned no message'});}
       try{ws.close();}catch(e){}
      };
      ws.onerror=()=>{if(!done){done=true;clearTimeout(t);res(null,{message:'Bridge unavailable. Start IRIS; Zepp may block local HTTP.'});}};
      return;
     }catch(e){}
    }
    res(null,{message:'Bridge unavailable. Start IRIS; Zepp may block local HTTP.'});
   });
 }
}));
