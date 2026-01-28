
# SLM Project Group 5

## Benchmarking Food Allergen Prediction Performance for Android On-Device using Small Language Models

**Course:** BITP 3453 – Mobile Application Development
**Semester:** Semester 1, 2025/2026
**Faculty:** Fakulti Teknologi Maklumat dan Komunikasi (FTMK), Universiti Teknikal Malaysia Melaka (UTeM)

---

## 📌 Project Purpose

This project focuses on the **design, implementation, and evaluation of an Android on-device inference application** that benchmarks the performance of **Small Language Models (SLMs)** for **food allergen prediction**.

The project aims to:

1. Implement an on-device inference pipeline using Small Language Models.
2. Systematically evaluate prediction quality, safety, and efficiency metrics.
3. Compare multiple SLMs under identical experimental conditions.
4. Present results through item-level prediction views and an aggregated performance dashboard.

---

## 🔄 Adaptation from Assignment

* This project is an **extension of the earlier assignment**, and all applicable specifications from the assignment are retained.
* The project allows experimentation with **different zero-shot prompting strategies** to improve prediction outcomes.
* Only **zero-shot prompting** is permitted.

---

## 🤖 Model for Evaluation

The application accepts a **list of food ingredients** as input and predicts the **presence of food allergens** as a **multi-label output**.

### Supported Allergens

* Milk
* Egg
* Peanut
* Tree nut
* Wheat
* Soy
* Fish
* Shellfish
* Sesame

An **empty output** indicates that **no allergens are detected**.

Each model is evaluated **independently** using:

* The same dataset
* The same prompt structure
* The same execution environment

This ensures **fair and consistent comparison** across models.

---

## 🧠 Evaluated Models

| No | Model Name             | Parameters | Quantization |
| -- | ---------------------- | ---------- | ------------ |
| 1  | Llama-3.2-1B-Instruct  | 1B         | Q4_K_M       |
| 2  | Llama-3.2-3B-Instruct  | 3B         | Q4_K_M       |
| 3  | Qwen2.5-1.5B-Instruct* | 1.5B       | Q4_K_M       |
| 4  | Qwen2.5-3B-Instruct    | 3B         | Q4_K_M       |
| 5  | Phi-3-mini-4k-instruct | 3.8B       | Q4           |
| 6  | Phi-3.5-mini-instruct  | 3.8B       | Q4_K_M       |
| 7  | Gemma-2B-instruct      | 2B         | Q4_K_M       |

* Baseline model used in the assignment.

---

## 📊 Model Evaluation Metrics

The application evaluates models using **three categories of metrics**.

---

### 1️⃣ Prediction Quality Metrics

Computed using **TP, FP, FN, TN** per allergen and aggregated across all samples.

Metrics include:

* **Precision**
* **Recall**
* **F1 Score (Macro & Micro)**
* **Exact Match Ratio (EMR)**
* **Hamming Loss**
* **False Negative Rate (FNR)**

These metrics support **multi-label evaluation** and emphasize **safety-critical performance**, especially recall and false negative rate.

---

### 2️⃣ Safety-Oriented Metrics

These metrics assess **trustworthiness and reliability** of model predictions:

* **Hallucination Rate**
  Detects allergens predicted that are not present in the input.

* **Over-Prediction Rate**
  Measures unnecessary allergen predictions that may reduce user trust.

* **Abstention Accuracy (No-Allergen Accuracy)**
  Evaluates the model’s ability to correctly predict an empty allergen set when no allergens exist.

---

### 3️⃣ On-Device Efficiency Metrics

All efficiency metrics are measured **on real Android devices**, without external computation:

* Inference Latency
* Time-To-First-Token (TTFT)
* Input Tokens per Second (ITPS)
* Output Tokens per Second (OTPS)
* Output Evaluation Time (OET)
* Total Response Time
* Java Heap Memory Usage
* Native Heap Memory Usage
* Proportional Set Size (PSS)

These metrics evaluate **runtime performance and resource consumption**.

---

## 🔁 Measurement Consistency & Repeatability

* All metrics are collected using:

  * Identical datasets
  * Identical prompts
  * Identical execution environments
* Results are averaged over **multiple runs** to reduce variability and improve reliability.

---

## 📈 Prediction Result Display & Dashboard

### Individual Food Item Prediction

* Displays predicted allergens for each input ingredient list.
* Enables item-level inspection and verification.

### Model Performance Dashboard

* Provides side-by-side comparison of all models.
* Aggregates:

  * Prediction quality metrics
  * Safety-oriented metrics
  * On-device efficiency metrics

### Metric Aggregation & Consistency

* Dashboard metrics accurately reflect individual prediction outcomes.
* Ensures consistency between item-level and model-level results.

---


## 🛠 Technologies Used

* Android Studio (Kotlin)
* Android NDK (C/C++)
* llama.cpp (on-device inference)
* Small Language Models (SLMs)
* Firebase (if applicable)

---

## 👥 Project Group

**SLM Project Group 5**
-FAZRINA BINTI MAJHARDEEN
-KAMINISHVARY A/P KERUPAYA
-NURKAYLA AALIYAH BINTI MOHAMMAD SAINI
---

