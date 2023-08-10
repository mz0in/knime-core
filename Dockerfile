# Use a base image
FROM base_image:tag

# Install any necessary dependencies
RUN apt-get update && apt-get install -y dependency1 dependency2

# Copy the app code to the container
COPY . /app

# Set the working directory
WORKDIR /app

# Build the app
RUN make

# Run the app
CMD ["./app"]