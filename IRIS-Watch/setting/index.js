AppSettingsPage({
 build(props){return View({style:{padding:'16px'}},[
  Text({style:{fontSize:'18px'}},'IRIS Remote pairing'),
  Text({},'Enable the experimental bridge in IRIS Settings. Paste its secret below. Your phone must keep IRIS running. No watch microphone streaming.'),
  TextInput({label:'Pairing secret',onChange:value=>props.settingsStorage.setItem('irisToken',value.trim())}),
  Button({label:'Forget pairing',onClick:()=>props.settingsStorage.removeItem('irisToken')})
 ]);}
});
