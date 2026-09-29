# 한국어 형태소 분석기(nori)를 넣은 Elasticsearch. 공식 이미지에는 포함되어 있지 않다.
ARG ES_VERSION=9.5.4
FROM docker.elastic.co/elasticsearch/elasticsearch:${ES_VERSION}

RUN bin/elasticsearch-plugin install --batch analysis-nori
