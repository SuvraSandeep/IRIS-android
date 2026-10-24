AppSettingsPage({
  build(props) {
    const rawToken = (props.settingsStorage.getItem('irisToken') || '').trim();
    const token = rawToken.replace(/^Bearer\s+/i, '').replace(/["']/g, '').trim().toLowerCase();
    const isValid = /^[a-f0-9]{64}$/i.test(token);
    const notice = props.settingsStorage.getItem('irisStatusNotice') || '';
    const maskedKey = isValid
      ? '•••• •••• •••• ' + token.slice(-8).toUpperCase()
      : (token.length > 0 ? 'Incomplete (' + token.length + '/64 chars)' : 'Not entered yet');

    return View({
      style: {
        backgroundColor: '#070816',
        minHeight: '100%',
        padding: '16px',
        color: '#F1F5F9',
        fontFamily: 'system-ui, -apple-system, sans-serif'
      }
    }, [
      // 1. Header Card
      View({
        style: {
          backgroundColor: '#0F1226',
          border: '1px solid #1E293B',
          borderTop: '3px solid #00F0FF',
          borderRadius: '14px',
          padding: '18px',
          marginBottom: '14px'
        }
      }, [
        Text({
          style: {
            fontSize: '20px',
            fontWeight: 'bold',
            color: '#00F0FF',
            marginBottom: '4px'
          }
        }, '⚡ IRIS Remote Settings'),
        Text({
          style: {
            fontSize: '13px',
            color: '#94A3B8'
          }
        }, 'Amazfit T-Rex 3 Companion Bridge · v17.0')
      ]),

      // 2. Connection Status Card
      View({
        style: {
          backgroundColor: '#0F1226',
          border: '1px solid ' + (isValid ? '#065F46' : '#701A2E'),
          borderRadius: '14px',
          padding: '16px',
          marginBottom: '14px'
        }
      }, [
        Text({
          style: {
            fontSize: '12px',
            fontWeight: 'bold',
            color: '#64748B',
            letterSpacing: '0.5px',
            marginBottom: '6px'
          }
        }, 'CONNECTION STATUS'),
        Text({
          style: {
            fontSize: '16px',
            fontWeight: 'bold',
            color: isValid ? '#00FF9D' : '#FB7185',
            marginBottom: '8px'
          }
        }, isValid ? '🟢 Connected & Ready' : '🟡 Ready to Pair (Enter Key Below)'),
        Text({
          style: {
            fontSize: '13px',
            color: '#38BDF8',
            fontFamily: 'monospace',
            marginBottom: '4px'
          }
        }, 'Pairing Key: ' + maskedKey),
        Text({
          style: {
            fontSize: '12px',
            color: '#94A3B8'
          }
        }, isValid
          ? 'Secure bridge active on port 18473. Your watch can now control IRIS.'
          : 'Copy the 64-character pairing key from your phone\'s IRIS app to connect.')
      ]),

      // Status Feedback Notice
      notice ? View({
        style: {
          backgroundColor: '#1E293B',
          border: '1px solid #38BDF8',
          borderRadius: '10px',
          padding: '12px 14px',
          marginBottom: '14px'
        }
      }, [
        Text({
          style: {
            fontSize: '13px',
            color: '#38BDF8'
          }
        }, '💬 ' + notice)
      ]) : null,

      // 3. Pairing Key Input Card
      View({
        style: {
          backgroundColor: '#0F1226',
          border: '1px solid #1E293B',
          borderRadius: '14px',
          padding: '16px',
          marginBottom: '14px'
        }
      }, [
        Text({
          style: {
            fontSize: '14px',
            fontWeight: 'bold',
            color: '#F1F5F9',
            marginBottom: '4px'
          }
        }, 'Pairing Secret Key'),
        Text({
          style: {
            fontSize: '12px',
            color: '#94A3B8',
            marginBottom: '12px'
          }
        }, 'From IRIS Android App → Settings → Amazfit T-Rex 3 Watch Remote.'),
        TextInput({
          label: 'Pairing Secret Key',
          settingsKey: 'irisToken',
          placeholder: 'Paste 64-character secret key here...',
          onChange: val => {
            const clean = (val || '').replace(/^Bearer\s+/i, '').replace(/["']/g, '').trim().toLowerCase();
            props.settingsStorage.setItem('irisToken', clean);
          }
        }),
        View({ style: { marginTop: '14px', marginBottom: '8px' } }, [
          Button({
            label: '✓ Save & Connect Key',
            color: 'primary',
            onClick: () => {
              const current = (props.settingsStorage.getItem('irisToken') || '').replace(/^Bearer\s+/i, '').replace(/["']/g, '').trim().toLowerCase();
              props.settingsStorage.setItem('irisToken', current);
              const valid = /^[a-f0-9]{64}$/i.test(current);
              props.settingsStorage.setItem('irisStatusNotice', valid
                ? 'Key saved & validated! T-Rex 3 is now paired.'
                : 'Key saved, but length is ' + current.length + '/64 chars. Please re-copy.');
            }
          })
        ]),
        View({}, [
          Button({
            label: 'Clear Stored Key',
            color: 'default',
            onClick: () => {
              props.settingsStorage.removeItem('irisToken');
              props.settingsStorage.setItem('irisStatusNotice', 'Pairing key cleared.');
            }
          })
        ])
      ]),

      // 4. Quick Connection Tests
      View({
        style: {
          backgroundColor: '#0F1226',
          border: '1px solid #1E293B',
          borderRadius: '14px',
          padding: '16px',
          marginBottom: '14px'
        }
      }, [
        Text({
          style: {
            fontSize: '14px',
            fontWeight: 'bold',
            color: '#F1F5F9',
            marginBottom: '4px'
          }
        }, 'Test Watch Connection'),
        Text({
          style: {
            fontSize: '12px',
            color: '#94A3B8',
            marginBottom: '12px'
          }
        }, 'Verify that communication between the watch and phone is working.'),
        View({ style: { marginBottom: '10px' } }, [
          Button({
            label: '📡 Ping Phone Bridge',
            color: 'default',
            onClick: () => {
              if (!isValid) {
                props.settingsStorage.setItem('irisStatusNotice', 'Please paste and save a valid 64-char key first.');
                return;
              }
              props.settingsStorage.setItem('irisStatusNotice', 'Checking phone bridge at 127.0.0.1:18473...');
              const headers = { Authorization: 'Bearer ' + token, 'X-IRIS-Sent-At': String(Date.now()) };
              const doTest = (url) => {
                return fetch(url, { method: 'GET', headers: headers }).catch(() => fetch({ url: url, method: 'GET', headers: headers }));
              };
              doTest('http://127.0.0.1:18473/ping')
                .then(res => {
                  props.settingsStorage.setItem('irisStatusNotice', '✓ Success: Phone bridge is ONLINE and responding!');
                })
                .catch(() => {
                  doTest('http://localhost:18473/ping')
                    .then(res => {
                      props.settingsStorage.setItem('irisStatusNotice', '✓ Success: Phone bridge is ONLINE on localhost!');
                    })
                    .catch(() => {
                      props.settingsStorage.setItem('irisStatusNotice', 'Phone bridge unreachable. Make sure the IRIS app is running on this phone.');
                    });
                });
            }
          })
        ]),
        View({}, [
          Button({
            label: '🔔 Test Ring Phone (Find Me)',
            color: 'default',
            onClick: () => {
              if (!isValid) {
                props.settingsStorage.setItem('irisStatusNotice', 'Please paste and save a valid 64-char key first.');
                return;
              }
              const headers = { Authorization: 'Bearer ' + token, 'X-IRIS-Sent-At': String(Date.now()) };
              fetch('http://127.0.0.1:18473/find', { method: 'POST', headers: headers })
                .catch(() => fetch({ url: 'http://127.0.0.1:18473/find', method: 'POST', headers: headers }))
                .then(() => {
                  props.settingsStorage.setItem('irisStatusNotice', '✓ Ring command sent! Your phone should be ringing now.');
                })
                .catch(() => {
                  props.settingsStorage.setItem('irisStatusNotice', 'Could not send ring command. Check that IRIS is running.');
                });
            }
          })
        ])
      ]),

      // 5. 3-Step Setup Guide
      View({
        style: {
          backgroundColor: '#0F1226',
          border: '1px solid #1E293B',
          borderRadius: '14px',
          padding: '16px'
        }
      }, [
        Text({
          style: {
            fontSize: '14px',
            fontWeight: 'bold',
            color: '#00F0FF',
            marginBottom: '10px'
          }
        }, 'Simple 3-Step Setup'),
        Text({ style: { fontSize: '13px', color: '#F1F5F9', marginBottom: '8px' } }, '1. Open the IRIS app on your phone → tap Settings.'),
        Text({ style: { fontSize: '13px', color: '#F1F5F9', marginBottom: '8px' } }, '2. Tap "Amazfit T-Rex 3 Watch Remote" → copy your pairing key.'),
        Text({ style: { fontSize: '13px', color: '#F1F5F9', marginBottom: '8px' } }, '3. Paste the key above and tap "Save & Connect Key".'),
        Text({ style: { fontSize: '13px', color: '#00FF9D', marginTop: '6px' } }, '✓ Open IRIS Remote on your T-Rex 3 watch and tap any button!')
      ])
    ]);
  }
});
