AppSettingsPage({
  build(props) {
    const rawToken = (props.settingsStorage.getItem('irisToken') || '').trim();
    const token = rawToken.replace(/^Bearer\s+/i, '').replace(/["']/g, '').trim().toLowerCase();
    const isValid = /^[a-f0-9]{64}$/i.test(token);
    const notice = props.settingsStorage.getItem('irisStatusNotice') || '';
    const maskedKey = isValid
      ? '•••• •••• •••• ' + token.slice(-8).toUpperCase()
      : (token.length > 0 ? 'Incomplete (' + token.length + '/64 chars)' : 'Not entered yet');

    const testBridge = (path, method, successMsg) => {
      if (!isValid) {
        props.settingsStorage.setItem('irisStatusNotice', '⚠️ Paste and save a valid 64-hex key first.');
        return;
      }
      props.settingsStorage.setItem('irisStatusNotice', '⏳ Contacting phone bridge at 127.0.0.1:18473' + path + '...');
      const headers = {
        Authorization: 'Bearer ' + token,
        'X-IRIS-Sent-At': String(Date.now()),
        'Content-Type': 'application/json'
      };

      const doReq = (url) => {
        return fetch(url, { method: method, headers: headers, mode: 'cors' })
          .then(res => {
            if (!res.ok) throw new Error('HTTP ' + res.status);
            return res.json().catch(() => res.text());
          });
      };

      doReq('http://127.0.0.1:18473' + path)
        .catch(() => doReq('http://localhost:18473' + path))
        .then(data => {
          const body = (typeof data === 'object' && data) ? (data.message || JSON.stringify(data)) : String(data);
          props.settingsStorage.setItem('irisStatusNotice', '✓ SUCCESS: ' + (successMsg || body));
        })
        .catch(() => {
          props.settingsStorage.setItem('irisStatusNotice', '✕ BRIDGE UNREACHABLE: Start IRIS on your phone (Settings → Amazfit T-Rex 3 → Start IRIS & Test).');
        });
    };

    return View({
      style: {
        backgroundColor: '#070816',
        minHeight: '100%',
        padding: '16px',
        display: 'flex',
        flexDirection: 'column',
        fontFamily: 'system-ui, -apple-system, sans-serif'
      }
    }, [
      // 1. Cyber Header Card
      View({
        style: {
          backgroundColor: '#0D1127',
          border: '1px solid #1E293B',
          borderTop: '3px solid #00F0FF',
          borderRadius: '14px',
          padding: '16px',
          marginBottom: '14px',
          display: 'flex',
          flexDirection: 'column'
        }
      }, [
        Text({
          paragraph: true,
          style: {
            fontSize: '18px',
            fontWeight: 'bold',
            color: '#00F0FF',
            display: 'block',
            marginBottom: '4px'
          }
        }, '⚡ IRIS REMOTE // T-REX 3'),
        Text({
          paragraph: true,
          style: {
            fontSize: '12px',
            color: '#94A3B8',
            display: 'block'
          }
        }, 'Cyberpunk Neural Wrist Link · v17.0.0')
      ]),

      // 2. Hardware Link Telemetry Card
      View({
        style: {
          backgroundColor: '#0D1127',
          border: '1px solid ' + (isValid ? '#065F46' : '#701A2E'),
          borderRadius: '14px',
          padding: '16px',
          marginBottom: '14px',
          display: 'flex',
          flexDirection: 'column'
        }
      }, [
        Text({
          paragraph: true,
          style: {
            fontSize: '11px',
            fontWeight: 'bold',
            color: '#64748B',
            letterSpacing: '1px',
            display: 'block',
            marginBottom: '6px'
          }
        }, 'HARDWARE TELEMETRY'),
        Text({
          paragraph: true,
          style: {
            fontSize: '15px',
            fontWeight: 'bold',
            color: isValid ? '#00FF9D' : '#FB7185',
            display: 'block',
            marginBottom: '8px'
          }
        }, isValid ? '● LINK ARMED & AUTHENTICATED' : '○ STANDBY // UNPAIRED'),
        Text({
          paragraph: true,
          style: {
            fontSize: '13px',
            color: '#38BDF8',
            fontFamily: 'monospace',
            display: 'block',
            marginBottom: '6px'
          }
        }, 'Active Key: ' + maskedKey),
        Text({
          paragraph: true,
          style: {
            fontSize: '12px',
            color: '#94A3B8',
            display: 'block'
          }
        }, isValid
          ? 'Secure bridge active on port 18473. Your T-Rex 3 watch can control IRIS.'
          : 'Copy the 64-hex pairing key from IRIS Settings on your phone to link.')
      ]),

      // Live Notice Banner (if any)
      notice ? View({
        style: {
          backgroundColor: notice.includes('SUCCESS') ? '#07241A' : (notice.includes('UNREACHABLE') || notice.includes('⚠️') ? '#240F18' : '#0B1E33'),
          border: '1px solid ' + (notice.includes('SUCCESS') ? '#00FF9D' : (notice.includes('UNREACHABLE') || notice.includes('⚠️') ? '#FB7185' : '#00F0FF')),
          borderRadius: '10px',
          padding: '12px 14px',
          marginBottom: '14px',
          display: 'flex',
          flexDirection: 'column'
        }
      }, [
        Text({
          paragraph: true,
          style: {
            fontSize: '13px',
            color: notice.includes('SUCCESS') ? '#00FF9D' : (notice.includes('UNREACHABLE') || notice.includes('⚠️') ? '#FB7185' : '#38BDF8'),
            display: 'block',
            lineHeight: '18px'
          }
        }, notice)
      ]) : null,

      // 3. Pairing Secret Key Input Card
      View({
        style: {
          backgroundColor: '#0D1127',
          border: '1px solid #1E293B',
          borderRadius: '14px',
          padding: '16px',
          marginBottom: '14px',
          display: 'flex',
          flexDirection: 'column'
        }
      }, [
        Text({
          paragraph: true,
          style: {
            fontSize: '14px',
            fontWeight: 'bold',
            color: '#F1F5F9',
            display: 'block',
            marginBottom: '4px'
          }
        }, 'Pairing Secret Key'),
        Text({
          paragraph: true,
          style: {
            fontSize: '12px',
            color: '#94A3B8',
            display: 'block',
            marginBottom: '12px'
          }
        }, 'From IRIS Android App → Settings → Amazfit T-Rex 3 Bridge.'),
        TextInput({
          label: 'Secret Key (64 hex characters)',
          settingsKey: 'irisToken',
          placeholder: 'Paste 64-hex key here...',
          onChange: val => {
            const clean = (val || '').replace(/^Bearer\s+/i, '').replace(/["']/g, '').trim().toLowerCase();
            props.settingsStorage.setItem('irisToken', clean);
          }
        }),
        View({
          style: {
            marginTop: '14px',
            marginBottom: '10px',
            display: 'flex',
            flexDirection: 'column'
          }
        }, [
          Button({
            label: '⚡ SAVE & ARM KEY',
            style: {
              background: 'linear-gradient(135deg, #7C3AED, #06B6D4)',
              backgroundColor: '#06B6D4',
              color: '#FFFFFF',
              borderRadius: '12px',
              fontWeight: 'bold',
              fontSize: '14px',
              border: 'none',
              padding: '12px',
              width: '100%',
              cursor: 'pointer'
            },
            onClick: () => {
              const current = (props.settingsStorage.getItem('irisToken') || '').replace(/^Bearer\s+/i, '').replace(/["']/g, '').trim().toLowerCase();
              props.settingsStorage.setItem('irisToken', current);
              const valid = /^[a-f0-9]{64}$/i.test(current);
              props.settingsStorage.setItem('irisStatusNotice', valid
                ? '✓ Key saved & validated! Tap "Ping Phone Bridge" below to verify.'
                : '⚠️ Key length is ' + current.length + '/64 characters. Please re-copy.');
            }
          })
        ]),
        View({
          style: {
            display: 'flex',
            flexDirection: 'column'
          }
        }, [
          Button({
            label: '✕ CLEAR STORED KEY',
            style: {
              backgroundColor: '#1E1220',
              color: '#FB7185',
              borderRadius: '12px',
              border: '1px solid #701A2E',
              fontWeight: 'bold',
              fontSize: '13px',
              padding: '10px',
              width: '100%',
              cursor: 'pointer'
            },
            onClick: () => {
              props.settingsStorage.removeItem('irisToken');
              props.settingsStorage.setItem('irisStatusNotice', 'Stored pairing secret cleared.');
            }
          })
        ])
      ]),

      // 4. Live Bridge Diagnostics & Hardware Controls
      View({
        style: {
          backgroundColor: '#0D1127',
          border: '1px solid #1E293B',
          borderRadius: '14px',
          padding: '16px',
          marginBottom: '14px',
          display: 'flex',
          flexDirection: 'column'
        }
      }, [
        Text({
          paragraph: true,
          style: {
            fontSize: '14px',
            fontWeight: 'bold',
            color: '#00F0FF',
            display: 'block',
            marginBottom: '4px'
          }
        }, 'Hardware Bridge Diagnostics'),
        Text({
          paragraph: true,
          style: {
            fontSize: '12px',
            color: '#94A3B8',
            display: 'block',
            marginBottom: '12px'
          }
        }, 'Directly probe connectivity between Zepp companion and your phone.'),
        View({
          style: {
            marginBottom: '10px',
            display: 'flex',
            flexDirection: 'column'
          }
        }, [
          Button({
            label: '📡 PING PHONE BRIDGE',
            style: {
              backgroundColor: '#0B2032',
              color: '#00F0FF',
              borderRadius: '12px',
              border: '1px solid #00F0FF',
              fontWeight: 'bold',
              fontSize: '14px',
              padding: '12px',
              width: '100%',
              cursor: 'pointer'
            },
            onClick: () => testBridge('/ping', 'GET', 'Phone bridge is ONLINE and ready!')
          })
        ]),
        View({
          style: {
            display: 'flex',
            flexDirection: 'column'
          }
        }, [
          Button({
            label: '🔔 TEST RING PHONE (FIND ME)',
            style: {
              backgroundColor: '#0A261A',
              color: '#00FF9D',
              borderRadius: '12px',
              border: '1px solid #00FF9D',
              fontWeight: 'bold',
              fontSize: '14px',
              padding: '12px',
              width: '100%',
              cursor: 'pointer'
            },
            onClick: () => testBridge('/find', 'POST', 'Ring command accepted! Phone is ringing.')
          })
        ])
      ]),

      // 5. Setup Guide
      View({
        style: {
          backgroundColor: '#0D1127',
          border: '1px solid #1E293B',
          borderRadius: '14px',
          padding: '16px',
          display: 'flex',
          flexDirection: 'column'
        }
      }, [
        Text({
          paragraph: true,
          style: {
            fontSize: '14px',
            fontWeight: 'bold',
            color: '#00F0FF',
            display: 'block',
            marginBottom: '10px'
          }
        }, '3-Step Connection Guide'),
        Text({ paragraph: true, style: { fontSize: '13px', color: '#F1F5F9', display: 'block', marginBottom: '8px' } }, '1. Open IRIS App on phone → Settings → tap "Amazfit T-Rex 3 Bridge".'),
        Text({ paragraph: true, style: { fontSize: '13px', color: '#F1F5F9', display: 'block', marginBottom: '8px' } }, '2. Tap "Start IRIS & Test bridge" → copy your pairing key.'),
        Text({ paragraph: true, style: { fontSize: '13px', color: '#F1F5F9', display: 'block', marginBottom: '8px' } }, '3. Paste the key above, tap "SAVE & ARM KEY", then tap "PING PHONE BRIDGE".'),
        Text({ paragraph: true, style: { fontSize: '13px', color: '#00FF9D', display: 'block', marginTop: '6px' } }, '✓ Open IRIS Remote on your T-Rex 3 watch and enjoy wrist control!')
      ])
    ]);
  }
});
