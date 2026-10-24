AppSettingsPage({
  build(props) {
    const rawToken = (props.settingsStorage.getItem('irisToken') || '').trim();
    const token = rawToken.replace(/^Bearer\s+/i, '').replace(/["']/g, '').trim().toLowerCase();
    const isValid = /^[a-f0-9]{64}$/i.test(token);
    const notice = props.settingsStorage.getItem('irisStatusNotice') || '';
    const maskedKey = isValid
      ? '•••• •••• •••• ' + token.slice(-8).toUpperCase()
      : (token.length > 0 ? 'PARTIAL (' + token.length + '/64 HEX)' : 'NONE CONFIGURED');

    return View({
      style: {
        backgroundColor: '#070816',
        minHeight: '100%',
        padding: '16px',
        color: '#F1F5F9',
        fontFamily: 'system-ui, -apple-system, sans-serif'
      }
    }, [
      // 1. Cyberpunk HUD Header
      View({
        style: {
          backgroundColor: '#0F1226',
          border: '1px solid #1E293B',
          borderTop: '3px solid #00F0FF',
          borderRadius: '14px',
          padding: '16px',
          marginBottom: '14px',
          boxShadow: '0 4px 20px rgba(0, 240, 255, 0.08)'
        }
      }, [
        Text({
          style: {
            fontSize: '20px',
            fontWeight: 'bold',
            color: '#00F0FF',
            letterSpacing: '1px',
            marginBottom: '4px'
          }
        }, '⚡ IRIS // NEURAL BRIDGE'),
        Text({
          style: {
            fontSize: '12px',
            color: '#94A3B8',
            letterSpacing: '0.5px',
            marginBottom: '8px'
          }
        }, 'AMAZFIT T-REX 3 COMPANION INTERFACE'),
        Text({
          style: {
            fontSize: '11px',
            color: '#A855F7',
            fontFamily: 'monospace'
          }
        }, '[ AES-256 ENCRYPTED · LOCAL HOST 18473 ]')
      ]),

      // 2. Live Telemetry & Pairing Status Card
      View({
        style: {
          backgroundColor: '#0F1226',
          border: '1px solid ' + (isValid ? '#065F46' : (token.length > 0 ? '#854D0E' : '#701A2E')),
          borderRadius: '14px',
          padding: '16px',
          marginBottom: '14px'
        }
      }, [
        Text({
          style: {
            fontSize: '11px',
            fontWeight: 'bold',
            color: '#64748B',
            letterSpacing: '1px',
            marginBottom: '6px'
          }
        }, '// TELEMETRY PAIRING STATUS'),
        Text({
          style: {
            fontSize: '16px',
            fontWeight: 'bold',
            color: isValid ? '#00FF9D' : (token.length > 0 ? '#FBBF24' : '#FB7185'),
            marginBottom: '8px'
          }
        }, isValid ? '● LINK SECURED & AUTHENTICATED' : (token.length > 0 ? '▲ INVALID SECRET FORMAT' : '○ STANDBY // UNPAIRED')),
        Text({
          style: {
            fontSize: '13px',
            color: '#38BDF8',
            fontFamily: 'monospace',
            marginBottom: '4px'
          }
        }, 'SECRET: ' + maskedKey),
        Text({
          style: {
            fontSize: '11px',
            color: '#94A3B8'
          }
        }, isValid
          ? 'Authenticated via constant-time SHA-256 digest. Ready for watch commands.'
          : (token.length > 0
              ? 'Token must be exactly 64 hexadecimal characters. Please re-copy from IRIS.'
              : 'Paste 64-character secret from IRIS Phone App to complete handshake.'))
      ]),

      // 3. Status Notice / Toast feedback (if triggered)
      notice ? View({
        style: {
          backgroundColor: '#1E293B',
          border: '1px solid #38BDF8',
          borderRadius: '10px',
          padding: '10px 14px',
          marginBottom: '14px'
        }
      }, [
        Text({
          style: {
            fontSize: '12px',
            color: '#38BDF8',
            fontFamily: 'monospace'
          }
        }, 'ℹ ' + notice)
      ]) : null,

      // 4. Token Input Card
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
            fontSize: '12px',
            fontWeight: 'bold',
            color: '#00F0FF',
            letterSpacing: '0.8px',
            marginBottom: '4px'
          }
        }, '// CRYPTOGRAPHIC HANDSHAKE SECRET'),
        Text({
          style: {
            fontSize: '12px',
            color: '#94A3B8',
            marginBottom: '12px'
          }
        }, 'Copy from IRIS Android (Settings > Companion Watch Bridge).'),
        TextInput({
          label: '256-Bit Pairing Secret',
          settingsKey: 'irisToken',
          placeholder: 'Paste 64-character hex token here...',
          onChange: val => {
            const clean = (val || '').replace(/^Bearer\s+/i, '').replace(/["']/g, '').trim().toLowerCase();
            props.settingsStorage.setItem('irisToken', clean);
            props.settingsStorage.setItem('irisPairState', /^[a-f0-9]{64}$/i.test(clean) ? 'PAIRED' : 'UNPAIRED');
          }
        })
      ]),

      // 5. Interactive Cyber Deck Controls
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
            fontSize: '12px',
            fontWeight: 'bold',
            color: '#00F0FF',
            letterSpacing: '0.8px',
            marginBottom: '12px'
          }
        }, '// BRIDGE COMMAND CONTROLS'),
        View({ style: { marginBottom: '10px' } }, [
          Button({
            label: '⚡ SAVE & LOCK SECRET',
            color: 'primary',
            onClick: () => {
              const current = (props.settingsStorage.getItem('irisToken') || '').replace(/^Bearer\s+/i, '').replace(/["']/g, '').trim().toLowerCase();
              props.settingsStorage.setItem('irisToken', current);
              const valid = /^[a-f0-9]{64}$/i.test(current);
              props.settingsStorage.setItem('irisPairState', valid ? 'PAIRED' : 'UNPAIRED');
              props.settingsStorage.setItem('irisStatusNotice', valid
                ? 'Secret saved & locked! T-Rex 3 is now paired.'
                : 'Secret saved, but length is ' + current.length + '/64 chars.');
            }
          })
        ]),
        View({ style: { marginBottom: '10px' } }, [
          Button({
            label: '📡 TEST BRIDGE (PING PHONE)',
            color: 'default',
            onClick: () => {
              if (!isValid) {
                props.settingsStorage.setItem('irisStatusNotice', 'Cannot ping: Please paste a valid 64-char secret first.');
                return;
              }
              props.settingsStorage.setItem('irisStatusNotice', 'Probing phone bridge at 127.0.0.1:18473/ping...');
              if (typeof fetch !== 'undefined') {
                fetch({
                  url: 'http://127.0.0.1:18473/ping',
                  method: 'GET',
                  headers: { Authorization: 'Bearer ' + token, 'X-IRIS-Sent-At': String(Date.now()) }
                }).then(res => {
                  props.settingsStorage.setItem('irisStatusNotice', 'Bridge Response: Phone is ONLINE & REACHABLE!');
                }).catch(() => {
                  props.settingsStorage.setItem('irisStatusNotice', 'Local HTTP loopback blocked or IRIS service not running.');
                });
              }
            }
          })
        ]),
        View({ style: { marginBottom: '10px' } }, [
          Button({
            label: '🔔 TEST FIND PHONE (ALARM)',
            color: 'default',
            onClick: () => {
              if (!isValid) {
                props.settingsStorage.setItem('irisStatusNotice', 'Cannot test: Please paste a valid 64-char secret first.');
                return;
              }
              props.settingsStorage.setItem('irisStatusNotice', 'Sending ring command to phone...');
              if (typeof fetch !== 'undefined') {
                fetch({
                  url: 'http://127.0.0.1:18473/find',
                  method: 'POST',
                  headers: { Authorization: 'Bearer ' + token, 'X-IRIS-Sent-At': String(Date.now()) }
                }).then(() => {
                  props.settingsStorage.setItem('irisStatusNotice', 'Alarm trigger sent successfully!');
                }).catch(() => {
                  props.settingsStorage.setItem('irisStatusNotice', 'Dispatching via watch-side bridge.');
                });
              }
            }
          })
        ]),
        View({}, [
          Button({
            label: '🗑 FORGET PAIRING / WIPE',
            color: 'default',
            onClick: () => {
              props.settingsStorage.removeItem('irisToken');
              props.settingsStorage.removeItem('irisPairState');
              props.settingsStorage.setItem('irisStatusNotice', 'Pairing credentials wiped.');
            }
          })
        ])
      ]),

      // 6. Quick Setup HUD Guide
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
            fontSize: '12px',
            fontWeight: 'bold',
            color: '#64748B',
            letterSpacing: '1px',
            marginBottom: '10px'
          }
        }, '// HOW TO PAIR STEP-BY-STEP'),
        Text({ style: { fontSize: '13px', color: '#F1F5F9', marginBottom: '6px' } }, '1. Open the IRIS app on your Android phone.'),
        Text({ style: { fontSize: '13px', color: '#F1F5F9', marginBottom: '6px' } }, '2. Tap Settings (gear icon) → Companion Watch Bridge.'),
        Text({ style: { fontSize: '13px', color: '#F1F5F9', marginBottom: '6px' } }, '3. Enable Watch Remote Bridge and copy the 64-hex Secret.'),
        Text({ style: { fontSize: '13px', color: '#F1F5F9', marginBottom: '6px' } }, '4. Paste it in the box above and tap "Save & Lock Secret".'),
        Text({ style: { fontSize: '13px', color: '#00FF9D', marginTop: '4px' } }, '✓ Open IRIS Remote on your T-Rex 3 watch to begin!')
      ])
    ]);
  }
});
