"""Regression for the actual hidden-status/stale-recording bug in the shipped layout."""
import xml.etree.ElementTree as ET
from pathlib import Path
root=ET.parse('app/src/main/res/layout/view_training.xml').getroot()
a='{http://schemas.android.com/apk/res/android}'
parents={child:node for node in root.iter() for child in node}
by_id={node.get(a+'id'):node for node in root.iter()}
status=by_id['@+id/wakeTrainingStatus']
node=status
while node in parents:
 node=parents[node]
 assert node.get(a+'id') not in {'@+id/wakeNormalState','@+id/wakeWizardState'}, 'Status disappears when panel toggles'
feedback=by_id['@+id/wakeWizardFeedback'].get(a+'text')
assert 'Recording' not in feedback, 'Initial state must not claim recording before model loading'
assert 'of 3' not in by_id['@+id/wakeWizardStep'].get(a+'text')
assert by_id['@+id/wakeWizardDots'].get(a+'text')=='0 / 8'
assert by_id['@+id/ownerRetryButton'].get(a+'visibility')=='gone'
assert by_id['@+id/wakeWizardCancel'].get(a+'text')=='Cancel training'
print('Passed training layout visibility and initial-state checks')
assert '@+id/ownerRecordButton' in by_id, 'Recording must have an explicit start action'
assert by_id['@+id/otherTrainingPanel'].get(a+'visibility')=='gone', 'Secondary controls must start collapsed'
assert '@+id/ownerVoiceMeter' in by_id and '@+id/ownerStepDots' in by_id
assert by_id['@+id/altWakeList'].get(a+'visibility')=='gone', 'Disabled alternate phrases should not look usable'
print('Passed guided voice studio controls and disclosure checks')
assert '@+id/ownerPhraseSuggestions' in by_id
assert by_id['@+id/ownerContinueButton'].get(a+'visibility')=='gone', 'Enrollment continuation starts unavailable'
assert by_id['@+id/ownerHeardPanel'].get(a+'visibility')=='gone'
assert by_id['@+id/ownerHeardText'].get(a+'accessibilityLiveRegion')=='polite'
assert by_id['@+id/ownerBeginVoice'].get(a+'layout_height')=='wrap_content', 'Primary action must accommodate larger text'
print('Passed phrase-preview comparison, continuation and larger-text layout checks')

for node in root.iter('Button'):
 assert node.get(a+'layout_height')=='wrap_content', 'Buttons must grow with text'
for node in root.iter('LinearLayout'):
 assert node.get(a+'orientation')!='horizontal', 'Training controls must stack on narrow screens'
for control in ['ownerHearPhrase','ownerHearPrompt','ownerPlayRecording','ownerStopPlayback','ownerBeginVoice','ownerProfileSummary']:
 assert '@+id/'+control in by_id
print('Passed stacked training actions and playback controls checks')
