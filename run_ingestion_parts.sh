#!/bin/bash
source venv/bin/activate
export ML_URL="http://localhost:8005"

echo "Part 1: 0 - 10,000 (Recreating Index)"
python3 training/ingestion/run_ingestion.py --ml-url $ML_URL --limit 10000 --offset 0
if [ $? -ne 0 ]; then echo "Failed at part 1"; exit 1; fi

echo "Part 2: 10,000 - 20,000 (Appending)"
python3 training/ingestion/run_ingestion.py --ml-url $ML_URL --limit 10000 --offset 10000 --append
if [ $? -ne 0 ]; then echo "Failed at part 2"; exit 1; fi

echo "Part 3: 20,000 - 30,000 (Appending)"
python3 training/ingestion/run_ingestion.py --ml-url $ML_URL --limit 10000 --offset 20000 --append
if [ $? -ne 0 ]; then echo "Failed at part 3"; exit 1; fi

echo "Part 4: 30,000 - 40,000 (Appending)"
python3 training/ingestion/run_ingestion.py --ml-url $ML_URL --limit 10000 --offset 30000 --append
if [ $? -ne 0 ]; then echo "Failed at part 4"; exit 1; fi

echo "Part 5: 40,000+ (Appending)"
python3 training/ingestion/run_ingestion.py --ml-url $ML_URL --limit 20000 --offset 40000 --append
if [ $? -ne 0 ]; then echo "Failed at part 5"; exit 1; fi

echo "All parts completed successfully!"
