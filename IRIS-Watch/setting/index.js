function extractHexKey(str) {
  if (!str) return '';
  const s = String(str).trim();
  const match = s.match(/[a-f0-9]{64}/i);
  if (match) return match[0].toLowerCase();
  const cleaned = s.replace(/[^a-f0-9]/gi, '').toLowerCase();
  return cleaned.length >= 64 ? cleaned.slice(-64) : cleaned;
}

AppSettingsPage({
  build(props) {
    const rawToken = props.settingsStorage.getItem('irisToken') || '';
    const token = extractHexKey(rawToken);
    const isValid = token.length === 64;
    const notice = props.settingsStorage.getItem('irisStatusNotice') || '';
    const maskedKey = isValid
      ? '•••• •••• •••• ' + token.slice(-8).toUpperCase()
      : (token.length > 0 ? 'Incomplete (' + token.length + '/64 hex chars)' : 'Not entered yet');

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

      const testWs = () => {
        return new Promise((resolve, reject) => {
          if (typeof WebSocket === 'undefined') {
            return reject(new Error('WebSocket unavailable'));
          }
          let resolved = false;
          let ws;
          try {
            ws = new WebSocket('ws://127.0.0.1:18473/ws');
          } catch (e) {
            return reject(e);
          }
          const timer = setTimeout(() => {
            if (!resolved) {
              resolved = true;
              try { ws.close(); } catch (e) {}
              reject(new Error('Connection timed out'));
            }
          }, 3000);

          ws.onopen = () => {
            ws.send(JSON.stringify({ action: path.replace(/^\//, ''), token: token, sentAt: Date.now() }));
          };
          ws.onmessage = (ev) => {
            if (resolved) return;
            resolved = true;
            clearTimeout(timer);
            try { ws.close(); } catch (e) {}
            try {
              const parsed = typeof ev.data === 'string' ? JSON.parse(ev.data) : ev.data;
              resolve(parsed && parsed.message ? parsed.message : String(ev.data));
            } catch (e) {
              resolve(String(ev.data));
            }
          };
          ws.onerror = (err) => {
            if (resolved) return;
            resolved = true;
            clearTimeout(timer);
            reject(new Error(err && err.message ? err.message : 'WebSocket probe failed'));
          };
        });
      };

      doReq('http://127.0.0.1:18473' + path)
        .catch(() => doReq('http://localhost:18473' + path))
        .catch(() => testWs())
        .then(data => {
          const body = (typeof data === 'object' && data) ? (data.message || JSON.stringify(data)) : String(data);
          props.settingsStorage.setItem('irisStatusNotice', '✓ SUCCESS: ' + (successMsg || body));
        })
        .catch(err => {
          props.settingsStorage.setItem('irisStatusNotice', '✕ BRIDGE UNREACHABLE: Ensure IRIS bridge is active on your phone (Settings → Amazfit T-Rex 3 Bridge). ' + (err && err.message ? '(' + err.message + ')' : ''));
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
        }, 'Cyberpunk Neural Wrist Link · v18.0.1')
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
            const key = extractHexKey(val);
            props.settingsStorage.setItem('irisToken', key);
            if (key.length === 64) {
              props.settingsStorage.setItem('irisStatusNotice', '✓ Valid 64-hex key detected! Tap "SAVE & ARM KEY" below.');
            }
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
              const raw = props.settingsStorage.getItem('irisToken') || '';
              const key = extractHexKey(raw);
              if (key.length === 64) {
                props.settingsStorage.setItem('irisToken', key);
                props.settingsStorage.setItem('irisStatusNotice', '✓ Key saved & armed (••••' + key.slice(-8).toUpperCase() + ')! Tap "PING PHONE BRIDGE" below to verify.');
              } else {
                props.settingsStorage.setItem('irisStatusNotice', '⚠️ Invalid key: found ' + key.length + '/64 hex characters. Please re-copy from IRIS Android app.');
              }
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
        Text({ paragraph: true, style: { fontSize: '13px', color: '#F1F5F9', display: 'block', marginBottom: '8px' } }, '2. Tap "Test phone bridge connection" → copy your pairing key.'),
        Text({ paragraph: true, style: { fontSize: '13px', color: '#F1F5F9', display: 'block', marginBottom: '8px' } }, '3. Paste the key above, tap "SAVE & ARM KEY", then tap "PING PHONE BRIDGE".'),
        Text({ paragraph: true, style: { fontSize: '13px', color: '#00FF9D', display: 'block', marginTop: '6px' } }, '✓ Open IRIS Remote on your T-Rex 3 watch and enjoy wrist control!')
      ])
    ]);
  }
});
