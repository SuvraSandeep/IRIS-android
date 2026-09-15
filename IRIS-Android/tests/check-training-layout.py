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
assert by_id['@+id/wakeWizardDots'].get(a+'text')=='0 / 14'
assert by_id['@+id/ownerRetryButton'].get(a+'visibility')=='gone'
assert by_id['@+id/wakeWizardCancel'].get(a+'text')=='Cancel training'
print('Passed training layout visibility and initial-state checks')
