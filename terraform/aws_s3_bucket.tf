resource "aws_s3_bucket" "notes" {
  bucket_prefix = "mrlsouza-notes-lab-"

  tags = {
    Name        = "notes"
    Environment = "lab"
  }
}

resource "aws_s3_bucket_public_access_block" "notes" {
  bucket = aws_s3_bucket.notes.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_lifecycle_configuration" "notes" {
  bucket = aws_s3_bucket.notes.id

  rule {
    id     = "abort-incomplete-multipart-uploads"
    status = "Enabled"

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}
