import { BaseSideService } from '@zeppos/zml/base-side';
import { settingsLib as settingsStorage } from '@zeppos/zml/base-side';
AppSideService(BaseSideService({
 onRequest(req,res){
  if(req.method!=='IRIS'||!['status','find','stop','talk'].includes(req.action)){res(null,{message:'Unsupported request'});return;}
  const token=(settingsStorage.getItem('irisToken')||'').trim();
  if(!/^[a-f0-9]{64}$/.test(token)){res(null,{message:'Pair in Zepp mini-app settings first'});return;}
  fetch({url:'http://127.0.0.1:18473/'+req.action,method:req.action==='status'?'GET':'POST',headers:{Authorization:'Bearer '+token}})
   .then(response=>{const body=typeof response.body==='string'?JSON.parse(response.body):response.body;res(null,{message:body?.message||'Phone returned no message'});})
   .catch(()=>res(null,{message:'Bridge unavailable. Start IRIS; Zepp may block local HTTP.'}));
 }
}));
