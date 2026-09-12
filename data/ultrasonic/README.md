# Ultrasonic / vibro-acoustic labelled data

Source of truth on phone:
`/data/data/com.aquascope/files/aquascope_data/locations.json`

- `baselineFeatures` = dry labels  
- `moistFeatures` = moist labels  

## Pull + train

1. Unlock phone → Allow USB debugging  
2. From repo root (PowerShell):

```powershell
.\tools\pull_and_train_ultrasonic.ps1
```

Or manually:

```powershell
mkdir data\ultrasonic -Force
adb exec-out run-as com.aquascope cat files/aquascope_data/locations.json > data\ultrasonic\locations.json
python tools\train_ultrasonic_scorer.py data\ultrasonic\locations.json
.\gradlew :app:installDebug
```

Trained weights land in `app/src/main/assets/ultrasonic/trained_weights.json` and are loaded at app start.
