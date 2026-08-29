from transformers import pipeline

classifier_pipeline = pipeline("text2text-generation", model="google/flan-t5-base", max_new_tokens=128)

prompt = """Classify the query below. Follow the exact format shown in the examples.

Query: What is the difference between a process and a thread?
Intent: conceptual
Reasoning Type: adaptive
Scope: single_topic
Sub-questions: What is a process?, What is a thread?, How do they differ?

Query: SQL vs NoSQL, which should I use for a high-write logging system?
Intent: comparative
Reasoning Type: strategic
Scope: multi_topic
Sub-questions: What are SQL's write characteristics?, What are NoSQL's write characteristics?, Which fits high-write logging?

Query: How do I fix a NullPointerException in Java?
Intent: debugging
Reasoning Type: commonsense
Scope: single_topic
Sub-questions: How do I fix a NullPointerException in Java?

Query: What is the difference between VC++ and C++?"""

outputs = classifier_pipeline(prompt)
print("Raw output:", outputs[0]["generated_text"])
