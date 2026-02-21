FROM lsiobase/ubuntu:jammy AS runtime-base

ENV DEBIAN_FRONTEND="noninteractive"

RUN apt-get update && apt-get install -y --no-install-recommends \
    ffmpeg \
    mkvtoolnix \
    libtesseract4 \
    build-essential \
    git \
    software-properties-common && \
    add-apt-repository 'ppa:deadsnakes/ppa' && apt-get update && apt-get install -y --no-install-recommends \
    python3.9 \
    python3.9-distutils \
    python3.9-dev && \
    apt-get -y clean && \
    rm -rf /var/lib/apt/lists/*

RUN mkdir -p /app/dotnet && \
    curl -o /tmp/dotnet-install.sh -L https://dot.net/v1/dotnet-install.sh && \
    chmod +x /tmp/dotnet-install.sh && \
    /tmp/dotnet-install.sh --version 8.0.24 --install-dir /app/dotnet --runtime dotnet

RUN mkdir -p /app/autosub && git clone --branch audio-filter https://github.com/jasongdove/AutoSub /app/autosub && \
    curl -o /tmp/get-pip.py -L https://bootstrap.pypa.io/get-pip.py && python3.9 /tmp/get-pip.py && \
    pip3 install --no-cache-dir -r /app/autosub/requirements.txt && \
    cd /app/autosub && pip3.9 install -e . && mkdir audio output && chmod 777 audio && chmod 777 output

RUN curl -o /app/autosub/model.tflite -L https://coqui.gateway.scarf.sh/english/coqui/v1.0.0-large-vocab/model.tflite
RUN curl -o /app/autosub/large_vocabulary.scorer -L https://coqui.gateway.scarf.sh/english/coqui/v1.0.0-large-vocab/large_vocabulary.scorer

ENV DOTNET_ROOT=/app/dotnet

FROM mcr.microsoft.com/dotnet/sdk:8.0-jammy AS build
RUN apt-get update && apt-get install -y \
    libtiff5-dev \
    libtesseract-dev \
    tesseract-ocr-eng \
    build-essential \
    cmake \
    pkg-config \
    git \
    unzip

WORKDIR /source

RUN git clone https://github.com/bubonic/VobSub2SRT && \
    cd VobSub2SRT && \
    ./configure && \
    make && \
    make install

RUN mkdir -p /tmp/pgstosrt && mkdir -p /app && \
    curl -o /tmp/pgstosrt/release.zip -L https://github.com/Tentacule/PgsToSrt/releases/download/v1.4.8/PgsToStr-1.4.8.zip && \
    unzip /tmp/pgstosrt/release.zip -d /tmp/pgstosrt && \
    mv /tmp/pgstosrt/ /app/pgstosrt && \
    mkdir -p /app/pgstosrt/tessdata && \
    curl -o /app/pgstosrt/tessdata/eng.traineddata -L https://github.com/tesseract-ocr/tessdata_best/raw/main/eng.traineddata     

COPY *.sln .
COPY *.csproj .
RUN dotnet restore -r linux-x64 .

COPY . ./
RUN dotnet publish TvRename.csproj -c release -o /app -r linux-x64 --self-contained false --no-restore -p:DebugType=Embedded -p:InformationalVersion=${INFO_VERSION}

FROM runtime-base
ENV PATH="/app/dotnet:${PATH}"
ENV CACHE_FOLDER="/cache"
WORKDIR /app
COPY --from=build /usr/local/bin/vobsub2srt /usr/local/bin/vobsub2srt
COPY --from=build /app ./
COPY wrapper.sh /app/wrapper.sh
ENTRYPOINT ["./wrapper.sh"]
