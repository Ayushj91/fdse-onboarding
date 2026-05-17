# Sample Customer Documents

These files simulate the variety of unstructured data sitting in the enterprise S3 bucket.
The LLM parser is expected to extract a structured Customer record from each.

---

## sample-email.txt

```
From: Sarah Johnson <sarah.johnson@techflow.io>
To: sales@ourcompany.com
Subject: Interested in Enterprise Plan

Hi,

I'm the CTO at TechFlow Inc. We have about 200 engineers and are interested
in your ENTERPRISE tier. Our billing address is 10 Downing Street, London, UK.
My direct line is +44-20-7946-0958.

Can we schedule a call this week?

Best,
Sarah Johnson
CTO, TechFlow Inc.
```

**Expected extraction:**
```json
{
  "firstName": "Sarah",
  "lastName": "Johnson",
  "email": "sarah.johnson@techflow.io",
  "phone": "+44-20-7946-0958",
  "company": "TechFlow Inc",
  "address": "10 Downing Street",
  "city": "London",
  "country": "UK",
  "planTier": "ENTERPRISE",
  "extractionConfidence": 0.95
}
```

---

## sample-csv-row.txt

```
Name,Email,Company,Plan,Phone
"Raj Patel","raj.patel@startupxyz.com","StartupXYZ","PRO","+91-98765-43210"
```

**Expected extraction:**
```json
{
  "firstName": "Raj",
  "lastName": "Patel",
  "email": "raj.patel@startupxyz.com",
  "company": "StartupXYZ",
  "phone": "+91-98765-43210",
  "planTier": "PRO",
  "extractionConfidence": 0.98
}
```

---

## sample-messy-note.txt

```
Spoke with Maria (maria_garcia@acmecorp.net) from Acme Corp at the Berlin conference.
She's keen on the free tier to start. Based in Munich, Germany. 
Might upgrade later. No phone collected.
```

**Expected extraction:**
```json
{
  "firstName": "Maria",
  "lastName": "Garcia",
  "email": "maria_garcia@acmecorp.net",
  "company": "Acme Corp",
  "city": "Munich",
  "country": "Germany",
  "planTier": "FREE",
  "extractionConfidence": 0.82
}
```
